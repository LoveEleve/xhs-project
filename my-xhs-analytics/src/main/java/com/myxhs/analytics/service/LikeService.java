package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.event.LikeEvent;
import com.myxhs.analytics.dto.request.LikeRequest;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.trace.MqTraceHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 点赞服务
 * <p>
 * 核心设计：
 * 1. Redis Set 存储点赞关系（权威数据源），SADD 天然幂等
 * 2. 双层幂等：@Idempotent 拦截网络抖动（5秒窗口）+ SADD 保证业务层幂等
 * 3. MQ 异步落库到 MySQL（最终一致性），消息体使用 JSON 序列化
 * 4. Pipeline 批量查询点赞状态（列表页优化）
 * </p>
 * <p>
 * Redis Key 设计：
 * - 笔记点赞集合：myxhs:like:note:{noteId}（Set，member=userId）
 * - 评论点赞集合：myxhs:like:comment:{commentId}（Set，member=userId）
 * - 用户点赞笔记反向索引：myxhs:like:user:{userId}:note（Set，member=noteId）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> likeAtomicScript;
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> unlikeAtomicScript;

    /** 业务类型：笔记 */
    private static final int BIZ_TYPE_NOTE = 1;
    /** 业务类型：评论 */
    private static final int BIZ_TYPE_COMMENT = 2;
    /** 批量查询最大 ID 数量 */
    private static final int MAX_BATCH_SIZE = 100;

    // ==================== 点赞 ====================

    /**
     * 点赞（笔记/评论）
     * <p>
     * 流程：SADD 添加点赞 → 维护反向索引 → MQ 同步落库 + 通知计数
     * SADD 天然幂等：返回 1=新增，返回 0=已存在（直接返回成功）
     * MQ 同步发送失败 → 回滚 Redis 操作，保证一致性
     * </p>
     *
     * @param userId  当前用户ID
     * @param request 点赞请求（bizType + bizId）
     */
    public void like(Long userId, LikeRequest request) {
        String likeKey = buildLikeKey(request.getBizType(), request.getBizId());
        boolean isNoteType = request.getBizType() == BIZ_TYPE_NOTE;
        String userLikeKey = isNoteType ? RedisKeyConstants.LIKE_SET + "user:" + userId + ":note" : "noop";

        // Lua 原子操作：SADD 正向索引 + SADD 反向索引（一次原子完成）
        Long added = stringRedisTemplate.execute(
                likeAtomicScript,
                java.util.List.of(likeKey, userLikeKey),
                String.valueOf(userId),
                String.valueOf(request.getBizId()),
                isNoteType ? "1" : "0"
        );

        if (added == null || added == 0) {
            // 已点赞，幂等返回（不抛异常，对用户友好）
            return;
        }

        log.info("[点赞] 点赞成功: userId={}, bizType={}, bizId={}", userId, request.getBizType(), request.getBizId());

        // MQ 同步落库（失败则回滚 Redis）
        if (!sendLikeEventSync(userId, request.getBizType(), request.getBizId(), "LIKE")) {
            // MQ 发送失败，回滚 Redis 操作（Lua 脚本保证原子回滚）
            rollbackLikeLua(likeKey, userLikeKey, String.valueOf(userId),
                    String.valueOf(request.getBizId()), isNoteType);
            log.error("[点赞] MQ发送失败已回滚Redis: userId={}, bizType={}, bizId={}",
                    userId, request.getBizType(), request.getBizId());
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.INTERNAL_ERROR, "点赞失败，请重试");
        }
    }

    /**
     * 回滚点赞 Redis 操作（MQ 发送失败时调用）
     */
    private void rollbackLikeLua(String likeKey, String userLikeKey, String member, String reverseMember, boolean isNoteType) {
        try {
            stringRedisTemplate.execute(
                    unlikeAtomicScript,
                    java.util.List.of(likeKey, userLikeKey),
                    member, reverseMember,
                    isNoteType ? "1" : "0"
            );
        } catch (Exception e) {
            log.error("[点赞] 回滚Redis失败: likeKey={}, userLikeKey={}", likeKey, userLikeKey, e);
        }
    }

    // ==================== 取消点赞 ====================

    /**
     * 取消点赞
     * <p>
     * SREM 返回 0 表示未点赞，直接返回（幂等）。
     * 发送 UNLIKE 事件到 MQ 让 Consumer 异步删除 DB 记录。
     * 不通过计数器维护 count——点赞数通过 SCARD 实时查询。
     * </p>
     *
     * @param userId  当前用户ID
     * @param request 取消点赞请求
     */
    public void unlike(Long userId, LikeRequest request) {
        String likeKey = buildLikeKey(request.getBizType(), request.getBizId());
        boolean isNoteType = request.getBizType() == BIZ_TYPE_NOTE;
        String userLikeKey = isNoteType ? RedisKeyConstants.LIKE_SET + "user:" + userId + ":note" : "noop";

        // Lua 原子操作：SREM 正向索引 + SREM 反向索引
        Long removed = stringRedisTemplate.execute(
                unlikeAtomicScript,
                java.util.List.of(likeKey, userLikeKey),
                String.valueOf(userId),
                String.valueOf(request.getBizId()),
                isNoteType ? "1" : "0"
        );

        if (removed == null || removed == 0) {
            // 未点赞，幂等返回
            return;
        }

        log.info("[点赞] 取消点赞成功: userId={}, bizType={}, bizId={}", userId, request.getBizType(), request.getBizId());

        // MQ 同步删除（失败则回滚 Redis）
        if (!sendLikeEventSync(userId, request.getBizType(), request.getBizId(), "UNLIKE")) {
            // MQ 发送失败，回滚 Redis 操作（Lua 脚本保证原子回滚）
            stringRedisTemplate.execute(likeAtomicScript,
                    java.util.List.of(likeKey, userLikeKey),
                    String.valueOf(userId),
                    String.valueOf(request.getBizId()),
                    isNoteType ? "1" : "0");
            log.error("[点赞] MQ发送失败已回滚Redis: userId={}, bizType={}, bizId={}",
                    userId, request.getBizType(), request.getBizId());
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.INTERNAL_ERROR, "取消点赞失败，请重试");
        }
    }

    // ==================== 查询点赞状态 ====================

    /**
     * 查询单个点赞状态
     *
     * @param userId  用户ID
     * @param bizType 业务类型
     * @param bizId   业务ID
     * @return true=已点赞，false=未点赞
     */
    public boolean isLiked(Long userId, int bizType, Long bizId) {
        String likeKey = buildLikeKey(bizType, bizId);
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(likeKey, String.valueOf(userId));
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 批量查询点赞状态（Pipeline 优化）
     * <p>
     * 笔记列表页一次查 N 条笔记的点赞状态，Pipeline 一次网络往返完成。
     * vs 逐条查询：N 次网络往返 × 1ms = Nms；Pipeline：1 次往返 < 1ms
     * </p>
     *
     * @param userId  用户ID
     * @param bizType 业务类型
     * @param bizIds  业务ID列表
     * @return Map<bizId, 是否已点赞>
     */
    public Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, List<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 限制批量查询数量，防止恶意请求
        List<Long> queryIds = bizIds.size() > MAX_BATCH_SIZE
                ? bizIds.subList(0, MAX_BATCH_SIZE) : bizIds;

        byte[] userIdBytes = String.valueOf(userId).getBytes(StandardCharsets.UTF_8);

        List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (Long bizId : queryIds) {
                        String key = buildLikeKey(bizType, bizId);
                        connection.setCommands().sIsMember(key.getBytes(StandardCharsets.UTF_8), userIdBytes);
                    }
                    return null;
                }
        );

        Map<Long, Boolean> statusMap = new LinkedHashMap<>();
        for (int i = 0; i < queryIds.size(); i++) {
            statusMap.put(queryIds.get(i), Boolean.TRUE.equals(results.get(i)));
        }
        return statusMap;
    }

    // ==================== 获取点赞数 ====================

    /**
     * 获取点赞数（直接从 Redis Set 的 SCARD 获取）
     *
     * @param bizType 业务类型
     * @param bizId   业务ID
     * @return 点赞数
     */
    public long getLikeCount(int bizType, Long bizId) {
        String likeKey = buildLikeKey(bizType, bizId);
        Long count = stringRedisTemplate.opsForSet().size(likeKey);
        return count != null ? count : 0L;
    }

    // ==================== 私有方法 ====================

    /**
     * 构建点赞 Redis Key
     * <p>
     * 笔记：myxhs:like:note:{noteId}
     * 评论：myxhs:like:comment:{commentId}
     * </p>
     */
    private String buildLikeKey(int bizType, Long bizId) {
        if (bizType == BIZ_TYPE_NOTE) {
            return RedisKeyConstants.LIKE_SET + "note:" + bizId;
        } else {
            return RedisKeyConstants.LIKE_SET + "comment:" + bizId;
        }
    }

    /**
     * 同步发送点赞/取消点赞事件到 MQ
     * <p>
     * Topic: SOCIAL_TOPIC, Tag: LIKE / UNLIKE
     * 消息体: JSON 格式的 LikeEvent
     * </p>
     *
     * @return true=发送成功, false=发送失败
     */
    private boolean sendLikeEventSync(Long userId, int bizType, Long bizId, String action) {
        try {
            LikeEvent event = LikeEvent.builder()
                    .userId(userId).bizType(bizType).bizId(bizId).action(action)
                    .actionTime(System.currentTimeMillis())
                    .build();
            String payload = objectMapper.writeValueAsString(event);
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000 // 超时 3 秒
            );

            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.debug("[点赞] MQ发送成功: action={}, userId={}, bizId={}", action, userId, bizId);
                return true;
            } else {
                log.error("[点赞] MQ发送状态异常: action={}, userId={}, bizId={}, status={}",
                        action, userId, bizId, sendResult.getSendStatus());
                return false;
            }
        } catch (Exception e) {
            log.error("[点赞] MQ发送异常: userId={}, bizType={}, bizId={}, action={}", userId, bizType, bizId, action, e);
            return false;
        }
    }
}
