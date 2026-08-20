package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.event.FavoriteEvent;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 收藏服务
 * <p>
 * 核心设计：
 * 1. Redis ZSet 存储收藏列表（score=收藏时间戳），天然排序+分页+去重
 * 2. ZSCORE 判断是否已收藏（O(1)），ZADD 幂等
 * 3. MQ 异步落库到 MySQL（最终一致性）
 * </p>
 * <p>
 * Redis Key 设计：
 * - 用户收藏列表：myxhs:favorite:{userId}（ZSet，member=noteId，score=收藏时间戳）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> favoriteAtomicScript;
    private final DefaultRedisScript<Long> unfavoriteAtomicScript;
    private final com.myxhs.analytics.feign.ContentFeignClient contentFeignClient;

    /** 收藏列表最大返回条数 */
    private static final int MAX_PAGE_SIZE = 50;

    // ==================== 收藏 ====================

    /**
     * 收藏笔记
     * <p>
     * ZSCORE 判断是否已收藏 → ZADD 添加收藏 → MQ 异步落库
     * </p>
     *
     * @param userId 当前用户ID
     * @param noteId 笔记ID
     */
    public void favorite(Long userId, Long noteId) {
        // T-116 修复：校验失败抛业务码（对照 T-103 like 修复——原静默 return 200"收藏成功"但无效果，误导客户端）
        if (!validateNote(noteId)) {
            throw new BizException(ResultCode.NOT_FOUND, "笔记不存在或未发布");
        }
        String key = RedisKeyConstants.FAVORITE_SET + userId;
        long currentTime = System.currentTimeMillis();

        // 【M16】Lua 脚本原子操作：ZSCORE 检查 + ZADD 写入
        Long result = stringRedisTemplate.execute(favoriteAtomicScript,
                Collections.singletonList(key),
                String.valueOf(noteId), String.valueOf(currentTime));

        if (result == null || result == 0) {
            return; // 已收藏，幂等
        }

        log.info("[收藏] 收藏成功: userId={}, noteId={}", userId, noteId);

        // MQ 同步落库——失败不回滚Redis(防Broker已消费但回滚导致Redis/DB不一致)
        try {
            sendFavoriteEvent(userId, noteId, "FAVORITE", currentTime);
        } catch (Exception e) {
            log.error("[收藏] MQ发送异常(Redis不回滚): userId={}, noteId={}", userId, noteId, e);
        }
    }

    // ==================== 取消收藏 ====================

    /**
     * 取消收藏
     *
     * @param userId 当前用户ID
     * @param noteId 笔记ID
     */
    public void unfavorite(Long userId, Long noteId) {
        String key = RedisKeyConstants.FAVORITE_SET + userId;

        // 先读取原始 score（用于 MQ 失败回滚时恢复原始收藏时间）
        Double originalScore = stringRedisTemplate.opsForZSet().score(key, String.valueOf(noteId));

        // Lua 原子 ZSCORE+ZREM：防止并发 favorite 写入新 score 后 ZREM→ZADD 覆盖
        Long removed = stringRedisTemplate.execute(
                unfavoriteAtomicScript,
                Collections.singletonList(key),
                String.valueOf(noteId));
        if (removed == null || removed == 0) {
            return; // 未收藏，幂等返回
        }

        log.info("[收藏] 取消收藏成功: userId={}, noteId={}", userId, noteId);

        // MQ 同步删除 + 通知计数服务（失败则回滚 Redis，用原始 score 恢复）
        if (!sendFavoriteEvent(userId, noteId, "UNFAVORITE", System.currentTimeMillis())) {
            // 回滚 ZADD 恢复（使用原始 score 而非当前时间，避免排序跳变）
            long rollbackScore = originalScore != null ? originalScore.longValue() : System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(noteId), rollbackScore);
            log.error("[收藏] MQ发送失败已回滚Redis(score={}): userId={}, noteId={}", rollbackScore, userId, noteId);
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.INTERNAL_ERROR, "取消收藏失败，请重试");
        }
    }

    // ==================== 查询收藏状态 ====================

    /**
     * 查询是否已收藏
     *
     * @param userId 用户ID
     * @param noteId 笔记ID
     * @return true=已收藏，false=未收藏
     */
    public boolean isFavorited(Long userId, Long noteId) {
        String key = RedisKeyConstants.FAVORITE_SET + userId;
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(noteId));
        return score != null;
    }

    // ==================== 收藏列表 ====================

    /**
     * 获取用户收藏列表（按收藏时间倒序）
     * <p>
     * ZSet reverseRange 分页查询，最新收藏在前。
     * </p>
     *
     * @param userId 用户ID
     * @param page   页码（从1开始）
     * @param size   每页条数
     * @return 收藏的笔记ID列表
     */
    public List<Long> getFavoriteList(Long userId, int page, int size) {
        size = Math.min(size, MAX_PAGE_SIZE);
        String key = RedisKeyConstants.FAVORITE_SET + userId;

        // ZSet 倒序分页
        long start = (long) (page - 1) * size;
        long end = start + size - 1;

        Set<String> noteIds = stringRedisTemplate.opsForZSet().reverseRange(key, start, end);
        if (noteIds == null || noteIds.isEmpty()) {
            return Collections.emptyList();
        }

        return noteIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户收藏总数
     *
     * @param userId 用户ID
     * @return 收藏总数
     */
    public long getFavoriteCount(Long userId) {
        String key = RedisKeyConstants.FAVORITE_SET + userId;
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }

    // ==================== 私有方法 ====================

    /**
     * O-Like-3：收藏前笔记存在性校验（batch-detail 降级跳过）
     */
    private boolean validateNote(Long noteId) {
        try {
            com.myxhs.common.response.R<Map<String, Object>> r =
                    contentFeignClient.batchGetNoteDetail(java.util.List.of(noteId));
            if (r == null || !r.isSuccess() || r.getData() == null) {
                return false;
            }
            return r.getData().get(String.valueOf(noteId)) instanceof Map;
        } catch (Exception e) {
            log.error("[收藏] 目标校验异常(拒绝写入): noteId={}", noteId, e);
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "内容服务暂不可用，请稍后重试");
        }
    }

    /**
     * 同步发送收藏/取消收藏事件到 MQ
     * <p>
     * Topic: SOCIAL_TOPIC
     * Tag: FAVORITE / UNFAVORITE
     * 消息体: JSON 格式的 FavoriteEvent
     * </p>
     *
     * @return true=发送成功, false=发送失败
     */
    private boolean sendFavoriteEvent(Long userId, Long noteId, String action, long timestamp) {
        try {
            FavoriteEvent event = FavoriteEvent.builder()
                    .userId(userId).noteId(noteId).action(action).actionTime(timestamp)
                    .build();
            String payload = objectMapper.writeValueAsString(event);
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000); // 超时 3 秒
            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.debug("[收藏] MQ发送成功: {}", payload);
                return true;
            } else {
                log.error("[收藏] MQ发送状态异常: action={}, userId={}, noteId={}, status={}",
                        action, userId, noteId, sendResult.getSendStatus());
                return false;
            }
        } catch (JsonProcessingException e) {
            log.error("[收藏] 事件序列化失败: userId={}, noteId={}", userId, noteId, e);
            return false;
        } catch (Exception e) {
            log.error("[收藏] MQ发送异常: userId={}, noteId={}, action={}", userId, noteId, action, e);
            return false;
        }
    }
}
