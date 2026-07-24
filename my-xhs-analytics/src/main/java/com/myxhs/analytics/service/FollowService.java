package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.response.FollowVO;
import com.myxhs.analytics.entity.Follow;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.response.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 关注服务
 * <p>
 * 核心设计：
 * 1. 关注关系存储在 Redis ZSet 中（权威数据源），score 为关注时间戳
 * 2. 关注/取关通过 Lua 脚本原子操作（ZADD/ZREM + INCR/DECR 计数）
 * 3. MySQL t_follow 表作为异步落库的持久化兜底（当前版本同步写入，后续接入 MQ 异步）
 * </p>
 * <p>
 * Redis Key 设计：
 * - 关注列表：myxhs:follow:list:{userId}（ZSet，member=targetUserId，score=时间戳）
 * - 粉丝列表：myxhs:follow:fans:{userId}（ZSet，member=followerUserId，score=时间戳）
 * - 关注数：myxhs:counter:user_following:{userId}（String）
 * - 粉丝数：myxhs:counter:user_follower:{userId}（String）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> followSelfScript;
    private final DefaultRedisScript<Long> followTargetScript;
    private final DefaultRedisScript<Long> unfollowSelfScript;
    private final DefaultRedisScript<Long> unfollowTargetScript;
    private final FollowMapper followMapper;
    private final IdGeneratorUtil idGeneratorUtil;

    /** 关注列表/粉丝列表最大返回条数 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 共同关注最大取出条数（防止大 V 关注列表过大导致 OOM） */
    private static final int MAX_COMMON_FOLLOW_FETCH = 5000;

    // ==================== 关注用户 ====================

    /**
     * 关注用户
     * <p>
     * 流程：校验不能关注自己 → Lua 脚本原子操作（ZSet + 计数） → 同步落库 MySQL
     * </p>
     *
     * @param userId       当前用户ID
     * @param targetUserId 目标用户ID
     */
    public void follow(Long userId, Long targetUserId) {
        // 1. 不能关注自己
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.CANNOT_FOLLOW_SELF);
        }

        // 2.【修复M6】拆分为两个 Lua 脚本，每个仅操作同一用户的 Key（Cluster 兼容）
        //   Step A: 当前用户侧（关注列表 + 关注数）
        String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;
        String followingCountKey = RedisKeyConstants.COUNTER + "user_following:" + userId;

        long currentTime = System.currentTimeMillis();

        Long selfResult = stringRedisTemplate.execute(
                followSelfScript,
                List.of(followingKey, followingCountKey),
                String.valueOf(targetUserId),
                String.valueOf(currentTime)
        );

        if (selfResult == null || selfResult == 0) {
            throw new BizException(ResultCode.ALREADY_FOLLOWED);
        }

        //   Step B: 目标用户侧（粉丝列表 + 粉丝数）
        String followerKey = RedisKeyConstants.FOLLOW_FANS + targetUserId;
        String followerCountKey = RedisKeyConstants.COUNTER + "user_follower:" + targetUserId;

        try {
            stringRedisTemplate.execute(
                    followTargetScript,
                    List.of(followerKey, followerCountKey),
                    String.valueOf(userId),
                    String.valueOf(currentTime)
            );
        } catch (Exception e) {
            // 目标用户侧写入失败不影响关注结果（对账任务修复粉丝侧）
            log.error("[关注] 目标用户粉丝列表写入失败（对账修复）: userId={}, targetUserId={}", userId, targetUserId, e);
        }

        log.info("[关注] 关注成功: userId={}, targetUserId={}", userId, targetUserId);

        // 3. 同步落库 MySQL（后续可改为 MQ 异步落库）
        try {
            Follow follow = new Follow();
            follow.setId(idGeneratorUtil.nextId());
            follow.setUserId(userId);
            follow.setFollowUserId(targetUserId);
            follow.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(currentTime), ZoneId.systemDefault()));
            followMapper.insert(follow);
        } catch (Exception e) {
            // MySQL 落库失败不影响关注操作（Redis 为权威数据源）
            // 后续通过对账任务修复不一致
            log.error("[关注] MySQL落库失败（不影响关注结果）: userId={}, targetUserId={}", userId, targetUserId, e);
        }
    }

    // ==================== 取关用户 ====================

    /**
     * 取关用户
     *
     * @param userId       当前用户ID
     * @param targetUserId 目标用户ID
     */
    public void unfollow(Long userId, Long targetUserId) {
        // 1. 不能取关自己
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.CANNOT_FOLLOW_SELF);
        }

        // 2.【修复M6】拆分为两个 Lua 脚本（Cluster 兼容）
        //   Step A: 当前用户侧（移除关注列表 + 关注数 -1）
        String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;
        String followingCountKey = RedisKeyConstants.COUNTER + "user_following:" + userId;

        Long selfResult = stringRedisTemplate.execute(
                unfollowSelfScript,
                List.of(followingKey, followingCountKey),
                String.valueOf(targetUserId)
        );

        if (selfResult == null || selfResult == 0) {
            throw new BizException(ResultCode.NOT_FOLLOWED);
        }

        //   Step B: 目标用户侧（移除粉丝列表 + 粉丝数 -1）
        String followerKey = RedisKeyConstants.FOLLOW_FANS + targetUserId;
        String followerCountKey = RedisKeyConstants.COUNTER + "user_follower:" + targetUserId;

        try {
            stringRedisTemplate.execute(
                    unfollowTargetScript,
                    List.of(followerKey, followerCountKey),
                    String.valueOf(userId)
            );
        } catch (Exception e) {
            log.error("[关注] 目标用户粉丝列表移除失败（对账修复）: userId={}, targetUserId={}", userId, targetUserId, e);
        }

        log.info("[关注] 取关成功: userId={}, targetUserId={}", userId, targetUserId);

        // 3. 同步删除 MySQL 记录
        try {
            followMapper.deleteByUserIdAndFollowUserId(userId, targetUserId);
        } catch (Exception e) {
            log.error("[关注] MySQL删除失败（不影响取关结果）: userId={}, targetUserId={}", userId, targetUserId, e);
        }
    }

    // ==================== 关注列表 ====================

    /**
     * 获取用户的关注列表（按关注时间倒序）
     *
     * @param userId   用户ID
     * @param page     页码（从1开始）
     * @param pageSize 每页条数
     * @return 关注列表
     */
    public List<FollowVO> getFollowingList(Long userId, int page, int pageSize) {
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        String key = RedisKeyConstants.FOLLOW_LIST + userId;

        // ZSet 按 score 倒序分页（reverseRangeWithScores）
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);

        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询是否互关（Pipeline 批量 ZSCORE，避免 N+1）
        String myFollowerKey = RedisKeyConstants.FOLLOW_FANS + userId;
        List<ZSetOperations.TypedTuple<String>> tupleList = new ArrayList<>(tuples);
        List<String> targetUserIds = tupleList.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .collect(Collectors.toList());

        // Pipeline 批量查询互关状态（一次网络往返替代 N 次 ZSCORE）
        List<Object> pipelineResults = stringRedisTemplate.executePipelined(
(org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    byte[] keyBytes = myFollowerKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (String targetId : targetUserIds) {
                        connection.zSetCommands().zScore(keyBytes, targetId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return null;
                }
        );

        List<FollowVO> result = new ArrayList<>(tupleList.size());
        for (int i = 0; i < tupleList.size(); i++) {
            ZSetOperations.TypedTuple<String> tuple = tupleList.get(i);
            FollowVO vo = new FollowVO();
            vo.setUserId(Long.valueOf(tuple.getValue()));

            Double score = tuple.getScore();
            if (score != null) {
                vo.setFollowedAt(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(score.longValue()), ZoneId.systemDefault()));
            }

            // Pipeline 返回的 score，非 null 表示互关
            vo.setIsFollowBack(pipelineResults.get(i) != null);
            result.add(vo);
        }

        return result;
    }

    // ==================== 粉丝列表 ====================

    /**
     * 获取用户的粉丝列表（按关注时间倒序）
     *
     * @param userId   用户ID
     * @param page     页码
     * @param pageSize 每页条数
     * @return 粉丝列表
     */
    public List<FollowVO> getFollowerList(Long userId, int page, int pageSize) {
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        String key = RedisKeyConstants.FOLLOW_FANS + userId;

        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);

        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        // Pipeline 批量查询是否互关（我是否也关注了对方，避免 N+1）
        String myFollowingKey = RedisKeyConstants.FOLLOW_LIST + userId;
        List<ZSetOperations.TypedTuple<String>> tupleList = new ArrayList<>(tuples);
        List<String> followerIds = tupleList.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .collect(Collectors.toList());

        List<Object> pipelineResults = stringRedisTemplate.executePipelined(
(org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    byte[] keyBytes = myFollowingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (String followerId : followerIds) {
                        connection.zSetCommands().zScore(keyBytes, followerId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return null;
                }
        );

        List<FollowVO> result = new ArrayList<>(tupleList.size());
        for (int i = 0; i < tupleList.size(); i++) {
            ZSetOperations.TypedTuple<String> tuple = tupleList.get(i);
            FollowVO vo = new FollowVO();
            vo.setUserId(Long.valueOf(tuple.getValue()));

            Double score = tuple.getScore();
            if (score != null) {
                vo.setFollowedAt(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(score.longValue()), ZoneId.systemDefault()));
            }

            vo.setIsFollowBack(pipelineResults.get(i) != null);
            result.add(vo);
        }

        return result;
    }

    // ==================== 共同关注 ====================

    /**
     * 获取共同关注列表
     * <p>
     * 分别取出两个用户的关注列表，在内存中求交集。
     * 限制最多取前 MAX_COMMON_FOLLOW_FETCH 个，防止大 V 关注列表过大导致 OOM。
     * </p>
     *
     * @param userId       当前用户ID
     * @param targetUserId 目标用户ID
     * @return 共同关注的用户ID列表
     */
    public List<Long> getCommonFollowing(Long userId, Long targetUserId) {
        String myKey = RedisKeyConstants.FOLLOW_LIST + userId;
        String targetKey = RedisKeyConstants.FOLLOW_LIST + targetUserId;

        // 【m19】使用 ZINTER 服务端求交集，避免 5000×2 数据拉回内存
        Set<String> common = stringRedisTemplate.opsForZSet()
                .intersect(myKey, targetKey);
        if (common == null || common.isEmpty()) {
            return Collections.emptyList();
        }

        return common.stream()
                .map(Long::valueOf)
                .limit(MAX_COMMON_FOLLOW_FETCH)
                .collect(Collectors.toList());
    }

    // ==================== 查询关注关系 ====================

    /**
     * 查询当前用户是否关注了目标用户
     *
     * @param userId       当前用户ID
     * @param targetUserId 目标用户ID
     * @return true=已关注，false=未关注
     */
    public boolean isFollowing(Long userId, Long targetUserId) {
        String key = RedisKeyConstants.FOLLOW_LIST + userId;
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(targetUserId));
        return score != null;
    }

    // ==================== 获取关注数/粉丝数 ====================

    /**
     * 获取用户的关注数
     */
    public long getFollowingCount(Long userId) {
        String key = RedisKeyConstants.COUNTER + "user_following:" + userId;
        String count = stringRedisTemplate.opsForValue().get(key);
        return count != null ? Long.parseLong(count) : 0L;
    }

    /**
     * 获取用户的粉丝数
     */
    public long getFollowerCount(Long userId) {
        String key = RedisKeyConstants.COUNTER + "user_follower:" + userId;
        String count = stringRedisTemplate.opsForValue().get(key);
        return count != null ? Long.parseLong(count) : 0L;
    }

    // ==================== 计数对账修复 ====================

    /**
     * 修复用户的关注数/粉丝数计数
     * <p>
     * 【为什么需要对账？】
     * Lua 脚本保证的是"执行隔离性"（不被其他命令打断），而非"事务回滚"。
     * 极端场景下（Redis OOM、进程被 kill、AOF 丢失），可能出现：
     * - 关注关系（ZSet）写入成功，但计数（INCR）未执行
     * - 计数与实际 ZSet 成员数不一致
     *
     * 【修复策略】
     * 以 ZSet 的 ZCARD 为准（ZSet 是权威数据源），覆盖 counter 值。
     * 建议通过定时任务（如每小时）扫描活跃用户进行对账。
     * </p>
     *
     * @param userId 用户ID
     * @return 修复结果描述
     */
    public String repairUserCounters(Long userId) {
        String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;
        String followerKey = RedisKeyConstants.FOLLOW_FANS + userId;
        String followingCountKey = RedisKeyConstants.COUNTER + "user_following:" + userId;
        String followerCountKey = RedisKeyConstants.COUNTER + "user_follower:" + userId;

        // 以 ZCARD 为准修复计数
        Long actualFollowing = stringRedisTemplate.opsForZSet().zCard(followingKey);
        Long actualFollower = stringRedisTemplate.opsForZSet().zCard(followerKey);

        String oldFollowing = stringRedisTemplate.opsForValue().get(followingCountKey);
        String oldFollower = stringRedisTemplate.opsForValue().get(followerCountKey);

        boolean repaired = false;
        StringBuilder sb = new StringBuilder();

        if (actualFollowing != null && !String.valueOf(actualFollowing).equals(oldFollowing)) {
            stringRedisTemplate.opsForValue().set(followingCountKey, String.valueOf(actualFollowing));
            sb.append(String.format("关注数修复: %s → %d; ", oldFollowing, actualFollowing));
            repaired = true;
        }

        if (actualFollower != null && !String.valueOf(actualFollower).equals(oldFollower)) {
            stringRedisTemplate.opsForValue().set(followerCountKey, String.valueOf(actualFollower));
            sb.append(String.format("粉丝数修复: %s → %d; ", oldFollower, actualFollower));
            repaired = true;
        }

        if (repaired) {
            log.warn("[关注] 计数对账修复: userId={}, {}", userId, sb);
            return sb.toString();
        }
        return "计数一致，无需修复";
    }

    /**
     * Redis ↔ MySQL 关系全量对账修复
     * <p>
     * 对比用户关注列表的 Redis ZSet 与 MySQL t_follow 表：
     * - Redis 有、MySQL 无 → INSERT 补上（follow 时 MySQL 写入失败）
     * - MySQL 有、Redis 无 → DELETE 清理（unfollow 时 MySQL 删除失败）
     * </p>
     * <p>
     * 修复策略：以 Redis ZSet 为准（Redis 是权威数据源），MySQL 为从。
     * </p>
     *
     * @param userId 用户ID
     * @return 对账结果
     */
    public String repairUserRelationships(Long userId) {
        String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;

        // 1. 获取 Redis ZSet 中所有关注目标
        Set<String> redisFollowing = stringRedisTemplate.opsForZSet()
                .range(followingKey, 0, -1);
        java.util.Set<Long> redisSet;
        if (redisFollowing == null || redisFollowing.isEmpty()) {
            redisSet = java.util.Collections.emptySet();
        } else {
            redisSet = redisFollowing.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        }

        // 2. 获取 MySQL 中所有关注目标
        java.util.List<Long> mysqlIds = followMapper.selectFollowUserIdsByUserId(userId);
        java.util.Set<Long> mysqlSet = new java.util.HashSet<>(mysqlIds);

        int inserted = 0;
        int deleted = 0;

        // 3. Redis 有、MySQL 无 → INSERT 补上
        for (Long targetId : redisSet) {
            if (!mysqlSet.contains(targetId)) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(targetId);
                followMapper.insert(follow);
                inserted++;
                log.info("[关注对账] 补缺失关系: userId={}, targetUserId={}", userId, targetId);
            }
        }

        // 4. MySQL 有、Redis 无 → DELETE 清理
        for (Long targetId : mysqlSet) {
            if (!redisSet.contains(targetId)) {
                followMapper.deleteByUserIdAndFollowUserId(userId, targetId);
                deleted++;
                log.info("[关注对账] 清理孤儿行: userId={}, targetUserId={}", userId, targetId);
            }
        }

        if (inserted > 0 || deleted > 0) {
            return String.format("关系修复: 补插入%d条, 清孤儿行%d条", inserted, deleted);
        }
        return "关系一致，无需修复";
    }
}
