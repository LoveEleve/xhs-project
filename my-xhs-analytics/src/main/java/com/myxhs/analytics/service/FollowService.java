package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.response.FollowVO;
import com.myxhs.analytics.entity.Follow;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
    private final RocketMQTemplate rocketMQTemplate;
    /** T-013: 用户存在性校验客户端 */
    private final com.myxhs.analytics.feign.UserFeignClient userFeignClient;

    /** 关注列表/粉丝列表最大返回条数 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 共同关注最大取出条数（防止大 V 关注列表过大导致 OOM） */
    private static final int MAX_COMMON_FOLLOW_FETCH = 5000;

    /**
     * 双源保护阈值：Redis 侧成员数低于 MySQL 侧的该比例时，视为"Redis 侧数据缺失"，
     * 对账跳过删除并触发反向重建（Redis 全丢/批量淘汰场景）。
     */
    private static final double RELATION_GUARD_MIN_RATIO = 0.5;

    /** 单次重建上限：超过则只告警不重建，避免超大列表在对账线程长时间阻塞 */
    private static final int RELATION_REBUILD_MAX = 10_000;

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

        // 1.5 T-013: target 用户存在性校验（防幽灵关注）
        if (!userExists(targetUserId)) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        // 1.6 T-016: 对方已拉黑当前用户 → 拒绝关注
        if (isBlockedBy(targetUserId, userId)) {
            throw new BizException(ResultCode.BLOCKED);
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

        // 4. 【修复R10】发送 MQ 更新 counter 服务关注/粉丝计数
        sendFollowCounterEvent(userId, targetUserId, "FOLLOW");

        // O-Like-1 修复：关注通知（type=3）通知被关注者（失败不影响关注主流程）
        sendFollowNotification(userId, targetUserId);
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

        // 4. 【修复R10】发送 MQ 更新 counter 服务关注/粉丝计数
        sendFollowCounterEvent(userId, targetUserId, "UNFOLLOW");
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
        // 下限保护：pageSize<=0 时 ZREVRANGE 会退化为全量（公开接口可拉大V全量列表）
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
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
        // 下限保护：pageSize<=0 时 ZREVRANGE 会退化为全量（公开接口可拉大V全量列表）
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
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
        String tempKey = RedisKeyConstants.FOLLOW_LIST + "common:" + userId + ":" + targetUserId + ":" + UUID.randomUUID();

        try {
            Long stored = stringRedisTemplate.opsForZSet().intersectAndStore(myKey, targetKey, tempKey);
            if (stored == null || stored == 0) {
                return Collections.emptyList();
            }
            stringRedisTemplate.expire(tempKey, java.time.Duration.ofSeconds(30));

            Set<String> common = stringRedisTemplate.opsForZSet().range(tempKey, 0, MAX_COMMON_FOLLOW_FETCH - 1);
            if (common == null || common.isEmpty()) {
                return Collections.emptyList();
            }
            return common.stream()
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
        } finally {
            stringRedisTemplate.delete(tempKey);
        }
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

        // 双源保护：ZSet 为 0 但计数器 > 0 时，先按 MySQL 重建 ZSet 再比对，
        // 避免"ZSet 丢失"被误判为"关系为空"并把计数直接清零。
        // 重建被跳过（超大列表 > RELATION_REBUILD_MAX）时两侧都标记为"未知"：不能据此写计数。
        boolean followingUnknown = false;
        boolean followerUnknown = false;
        if (actualFollowing != null && actualFollowing == 0 && parseLongSafe(oldFollowing) > 0) {
            int rebuilt = rebuildFollowingList(userId);
            if (rebuilt > 0) {
                actualFollowing = stringRedisTemplate.opsForZSet().zCard(followingKey);
            } else if (rebuilt < 0) {
                followingUnknown = true;
                log.warn("[关注] 关注列表超重建上限, 本轮跳过计数校准: userId={}", userId);
            }
        }
        if (actualFollower != null && actualFollower == 0 && parseLongSafe(oldFollower) > 0) {
            int rebuilt = rebuildFollowerList(userId);
            if (rebuilt > 0) {
                actualFollower = stringRedisTemplate.opsForZSet().zCard(followerKey);
            } else if (rebuilt < 0) {
                followerUnknown = true;
                log.warn("[关注] 粉丝列表超重建上限, 本轮跳过计数校准: userId={}", userId);
            }
        }

        boolean repaired = false;
        StringBuilder sb = new StringBuilder();

        if (!followingUnknown && actualFollowing != null && !String.valueOf(actualFollowing).equals(oldFollowing)) {
            stringRedisTemplate.opsForValue().set(followingCountKey, String.valueOf(actualFollowing));
            sb.append(String.format("关注数修复: %s → %d; ", oldFollowing, actualFollowing));
            repaired = true;
        }

        if (!followerUnknown && actualFollower != null && !String.valueOf(actualFollower).equals(oldFollower)) {
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

    public void syncCountersToCounterModule(Long userId) {
        long follow = getFollowingCount(userId);
        long follower = getFollowerCount(userId);
        // 必须带 TTL：SET 会清除既有 TTL，原实现把 counter 模块维护的 30 天续期键改成永久 key（内存泄漏面）。
        // 口径提示：这里仍是"绝对覆盖"（绕过 counter 的增量/去重语义），已登记为待改造项。
        stringRedisTemplate.opsForValue().set(RedisKeyConstants.COUNTER + "2:" + userId + ":7",
                String.valueOf(follow), java.time.Duration.ofDays(30));
        stringRedisTemplate.opsForValue().set(RedisKeyConstants.COUNTER + "2:" + userId + ":6",
                String.valueOf(follower), java.time.Duration.ofDays(30));
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

        // 1. 获取 Redis ZSet 中所有关注目标及其 score（关注时间戳）
        Set<ZSetOperations.TypedTuple<String>> redisFollowing = stringRedisTemplate.opsForZSet()
                .rangeWithScores(followingKey, 0, -1);
        java.util.Map<Long, Double> redisScoreMap = new java.util.LinkedHashMap<>();
        if (redisFollowing != null) {
            for (ZSetOperations.TypedTuple<String> t : redisFollowing) {
                if (t.getValue() != null) {
                    redisScoreMap.put(Long.valueOf(t.getValue()), t.getScore());
                }
            }
        }

        // 2. 获取 MySQL 中所有关注目标
        java.util.List<Long> mysqlIds = followMapper.selectFollowUserIdsByUserId(userId);
        java.util.Set<Long> mysqlSet = new java.util.HashSet<>(mysqlIds);

        // 双源保护：Redis 侧缺失时不做删除——
        // 1) 完全缺失（redis=0，MySQL 有数据）：MySQL 是唯一幸存副本 → 按 MySQL 反向重建 ZSet；
        // 2) 部分缺失（低于阈值但非 0）：无法区分"Redis 丢数据"与"MySQL 有历史孤儿行"，
        //    既不删除也不重建（避免把陈旧关系复活），只报告、留待人工核对。
        if (isRedisSideMissing(redisScoreMap.size(), mysqlSet.size())) {
            if (redisScoreMap.isEmpty()) {
                int rebuilt = rebuildFollowingList(userId);
                log.warn("[关注对账] Redis 侧完全缺失(redis=0, mysql={}), 跳过删除并重建关注列表: userId={}, rebuilt={}",
                        mysqlSet.size(), userId, rebuilt);
                return String.format("Redis 侧缺失保护: 跳过删除, 重建关注列表 %d 条", rebuilt);
            }
            log.warn("[关注对账] Redis 侧疑似部分缺失(redis={}, mysql={}), 跳过删除与重建待人工核对: userId={}",
                    redisScoreMap.size(), mysqlSet.size(), userId);
            return String.format("Redis 侧部分缺失保护: 跳过删除(redis=%d, mysql=%d)",
                    redisScoreMap.size(), mysqlSet.size());
        }

        int inserted = 0;
        int deleted = 0;

        // 3. Redis 有、MySQL 无 → INSERT 补上（用 ZSet score 作为 createdAt）
        for (java.util.Map.Entry<Long, Double> entry : redisScoreMap.entrySet()) {
            Long targetId = entry.getKey();
            if (!mysqlSet.contains(targetId)) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(targetId);
                if (entry.getValue() != null) {
                    follow.setCreatedAt(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(entry.getValue().longValue()), ZoneId.systemDefault()));
                } else {
                    follow.setCreatedAt(LocalDateTime.now());
                }
                followMapper.insert(follow);
                inserted++;
                log.info("[关注对账] 补缺失关系: userId={}, targetUserId={}", userId, targetId);
            }
        }

        // 4. MySQL 有、Redis 无 → DELETE 清理
        for (Long targetId : mysqlSet) {
            if (!redisScoreMap.containsKey(targetId)) {
                followMapper.deleteByUserIdAndFollowUserId(userId, targetId);
                deleted++;
                log.info("[关注对账] 清理孤儿行: userId={}, targetUserId={}", userId, targetId);
            }
        }

        if (inserted > 0 || deleted > 0) {
            return String.format("关系修复: 关注侧补插入%d条, 清孤儿行%d条", inserted, deleted);
        }
        return "关系一致，无需修复";
    }

    /**
     * 粉丝侧关系对账（修复 FOLLOW_FANS 一致性）
     * <p>Step B 失败时粉丝 ZSet 成员可能缺失——以 ZSet 为准补/删 MySQL</p>
     */
    public String repairFollowerRelationships(Long userId) {
        String followerKey = RedisKeyConstants.FOLLOW_FANS + userId;

        Set<ZSetOperations.TypedTuple<String>> redisFollowers = stringRedisTemplate.opsForZSet()
                .rangeWithScores(followerKey, 0, -1);
        java.util.Map<Long, Double> redisScoreMap = new java.util.LinkedHashMap<>();
        if (redisFollowers != null) {
            for (ZSetOperations.TypedTuple<String> t : redisFollowers) {
                if (t.getValue() != null) {
                    redisScoreMap.put(Long.valueOf(t.getValue()), t.getScore());
                }
            }
        }

        java.util.List<Long> mysqlIds = followMapper.selectFollowerUserIdsByUserId(userId);
        java.util.Set<Long> mysqlSet = new java.util.HashSet<>(mysqlIds);

        // 双源保护：同关注侧——完全缺失按 MySQL 重建，部分缺失只跳过删除（不做重建，避免复活陈旧关系）
        if (isRedisSideMissing(redisScoreMap.size(), mysqlSet.size())) {
            if (redisScoreMap.isEmpty()) {
                int rebuilt = rebuildFollowerList(userId);
                log.warn("[关注对账] Redis 侧完全缺失(redis=0, mysql={}), 跳过删除并重建粉丝列表: userId={}, rebuilt={}",
                        mysqlSet.size(), userId, rebuilt);
                return String.format("Redis 侧缺失保护: 跳过删除, 重建粉丝列表 %d 条", rebuilt);
            }
            log.warn("[关注对账] Redis 侧疑似部分缺失(redis={}, mysql={}), 跳过删除与重建待人工核对: userId={}",
                    redisScoreMap.size(), mysqlSet.size(), userId);
            return String.format("Redis 侧部分缺失保护: 跳过删除(redis=%d, mysql=%d)",
                    redisScoreMap.size(), mysqlSet.size());
        }

        int inserted = 0, deleted = 0;
        for (java.util.Map.Entry<Long, Double> entry : redisScoreMap.entrySet()) {
            Long followerId = entry.getKey();
            if (!mysqlSet.contains(followerId)) {
                Follow follow = new Follow();
                follow.setUserId(followerId);
                follow.setFollowUserId(userId);
                if (entry.getValue() != null) {
                    follow.setCreatedAt(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(entry.getValue().longValue()), ZoneId.systemDefault()));
                } else {
                    follow.setCreatedAt(LocalDateTime.now());
                }
                followMapper.insert(follow);
                inserted++;
            }
        }
        for (Long followerId : mysqlSet) {
            if (!redisScoreMap.containsKey(followerId)) {
                followMapper.deleteByUserIdAndFollowUserId(followerId, userId);
                deleted++;
            }
        }
        return inserted > 0 || deleted > 0
                ? String.format("粉丝侧修复: 补插入%d条, 清孤儿行%d条", inserted, deleted)
                : "粉丝侧一致，无需修复";
    }

    // ==================== 双源保护与重建 ====================

    /**
     * Redis 侧是否疑似数据缺失
     * <p>
     * 判据：MySQL 有数据，而 Redis 侧成员数不足 MySQL 的一半（含 Redis 为 0）。
     * 命中后对账不做删除，防止 Redis 故障（丢 key / 批量淘汰 / 未开 AOF 重启）
     * 演变成 MySQL 兜底数据被清空。
     * </p>
     */
    private boolean isRedisSideMissing(int redisSize, int mysqlSize) {
        if (mysqlSize <= 0) {
            return false;
        }
        return redisSize < mysqlSize * RELATION_GUARD_MIN_RATIO;
    }

    /**
     * 用 MySQL 兜底数据重建关注列表 ZSet（score = 关注时间）
     *
     * @return 重建条数；-1 表示超过单次重建上限被跳过
     */
    public int rebuildFollowingList(Long userId) {
        return rebuildList(RedisKeyConstants.FOLLOW_LIST + userId,
                followMapper.selectFollowRowsByUserId(userId), true);
    }

    /**
     * 用 MySQL 兜底数据重建粉丝列表 ZSet（score = 关注时间）
     *
     * @return 重建条数；-1 表示超过单次重建上限被跳过
     */
    public int rebuildFollowerList(Long userId) {
        return rebuildList(RedisKeyConstants.FOLLOW_FANS + userId,
                followMapper.selectFollowerRowsByUserId(userId), false);
    }

    /**
     * ZSet 批量重建：关注侧取 follow_user_id，粉丝侧取 user_id；score 用 created_at 还原关注时间
     */
    private int rebuildList(String key, List<Follow> rows, boolean followingSide) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > RELATION_REBUILD_MAX) {
            log.warn("[关注对账] 重建超限跳过: key={}, rows={}, limit={}", key, rows.size(), RELATION_REBUILD_MAX);
            return -1;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (Follow row : rows) {
            Long member = followingSide ? row.getFollowUserId() : row.getUserId();
            if (member == null) {
                continue;
            }
            double score = row.getCreatedAt() != null
                    ? row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : System.currentTimeMillis();
            tuples.add(new DefaultTypedTuple<>(String.valueOf(member), score));
        }
        if (tuples.isEmpty()) {
            return 0;
        }
        stringRedisTemplate.opsForZSet().add(key, tuples);
        log.info("[关注对账] 重建列表: key={}, size={}", key, tuples.size());
        return tuples.size();
    }

    private long parseLongSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * O-Like-1 修复：发送关注通知（type=3）到 NOTIFICATION_TOPIC
     * <p>
     * 关注成功（Lua selfResult=1 且非重复关注）后通知被关注者；
     * 对齐 CommentService 通知模式，senderName 暂不填（O-Comment-2 统一观察项）。
     * </p>
     */
    private void sendFollowNotification(Long senderId, Long targetUserId) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", 3); // 3=关注
            notification.put("senderId", senderId);
            notification.put("targetUserId", targetUserId);
            notification.put("targetId", targetUserId);
            // R5：targetType 不填——DTO 语义为 1-笔记 2-商品 3-订单，无"用户"类型；null 避免语义化错误
            notification.put("content", "关注了你");
            rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC",
                    MqTraceHelper.wrapWithTraceContext(
                            org.springframework.messaging.support.MessageBuilder.withPayload(notification).build()),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("[关注] 关注通知已发送: senderId={}, targetUserId={}", senderId, targetUserId);
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.warn("[关注] 关注通知发送失败(不影响关注): targetUserId={}", targetUserId, e);
                        }
                    });
        } catch (Exception e) {
            log.warn("[关注] 关注通知发送异常(不影响关注): targetUserId={}, err={}", targetUserId, e.getMessage());
        }
    }

    /**
     * 【修复R10】发送关注/取关计数事件到 counter 服务
     * <p>
     * FOLLOW: follower FOLLOWING+1, followee FOLLOWER+1
     * UNFOLLOW: follower FOLLOWING-1, followee FOLLOWER-1
     * 使用 syncSend 确保关注操作返回前 MQ 已确认接收（不影响关注本身的 Lua 结果）。
     * </p>
     */
    private void sendFollowCounterEvent(Long followerUserId, Long followeeUserId, String action) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("followerUserId", followerUserId);
            event.put("followeeUserId", followeeUserId);
            // 乱序治理：事件时间戳供消费端版本门拒绝"后到的旧事件"（与 like/favorite 同构）
            event.put("actionTime", System.currentTimeMillis());
            event.put("action", action);
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceContext(
                            org.springframework.messaging.support.MessageBuilder.withPayload(event).build()),
                    3000);
            if (sendResult.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.warn("[关注计数] MQ发送状态异常: follower={}, followee={}, action={}, status={}",
                        followerUserId, followeeUserId, action, sendResult.getSendStatus());
                return;
            }
            log.debug("[关注计数] MQ发送成功: follower={}, followee={}, action={}",
                    followerUserId, followeeUserId, action);
        } catch (Exception e) {
            // syncSend 失败不影响关注操作本体的结果，由凌晨对账兜底
            log.error("[关注计数] MQ发送失败: follower={}, followee={}, action={}",
                    followerUserId, followeeUserId, action, e);
        }
    }

    /**
     * T-013: 校验目标用户存在（Feign 内部调用；失败 fail-closed）
     */
    private boolean userExists(Long targetUserId) {
        try {
            com.myxhs.common.response.R<Boolean> r = userFeignClient.userExists(targetUserId);
            return r != null && r.isSuccess() && Boolean.TRUE.equals(r.getData());
        } catch (Exception e) {
            log.error("[关注] 用户存在性校验失败(拒绝), target={}", targetUserId, e);
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "用户服务暂不可用");
        }
    }

    /**
     * T-016: 对方是否已拉黑当前用户（Redis Set 直读）
     */
    private boolean isBlockedBy(Long targetUserId, Long userId) {
        try {
            Boolean blocked = stringRedisTemplate.opsForSet().isMember(
                    "myxhs:user:block:" + targetUserId, String.valueOf(userId));
            return Boolean.TRUE.equals(blocked);
        } catch (Exception e) {
            log.warn("[关注] 拉黑状态读取失败(放行), target={}, user={}", targetUserId, userId);
            return false;
        }
    }

}
