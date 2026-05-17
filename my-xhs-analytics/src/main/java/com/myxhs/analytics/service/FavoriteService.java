package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.event.FavoriteEvent;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.trace.MqTraceHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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
        String key = RedisKeyConstants.FAVORITE_SET + userId;

        // 1. 检查是否已收藏（ZSCORE O(1)）
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(noteId));
        if (score != null) {
            // 已收藏，幂等返回
            return;
        }

        // 2. ZADD 添加收藏（score=当前时间戳，用于按时间排序）
        long currentTime = System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(noteId), currentTime);

        log.info("[收藏] 收藏成功: userId={}, noteId={}", userId, noteId);

        // 3. MQ 异步落库 + 通知计数服务
        sendFavoriteEvent(userId, noteId, "FAVORITE", currentTime);
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

        // 1. ZREM 移除收藏（返回移除的元素数量）
        Long removed = stringRedisTemplate.opsForZSet().remove(key, String.valueOf(noteId));
        if (removed == null || removed == 0) {
            // 未收藏，幂等返回
            return;
        }

        log.info("[收藏] 取消收藏成功: userId={}, noteId={}", userId, noteId);

        // 2. MQ 异步删除 + 通知计数服务
        sendFavoriteEvent(userId, noteId, "UNFAVORITE", 0);
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
     * 发送收藏/取消收藏事件到 MQ
     * <p>
     * Topic: SOCIAL_TOPIC
     * Tag: FAVORITE / UNFAVORITE
     * 消息体: JSON 格式的 FavoriteEvent
     * </p>
     */
    private void sendFavoriteEvent(Long userId, Long noteId, String action, long timestamp) {
        try {
            FavoriteEvent event = FavoriteEvent.builder()
                    .userId(userId).noteId(noteId).action(action).timestamp(timestamp)
                    .build();
            String payload = objectMapper.writeValueAsString(event);
            rocketMQTemplate.asyncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("[收藏] MQ发送成功: {}, msgId={}", payload, sendResult.getMsgId());
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("[收藏] MQ发送失败: {}", payload, e);
                        }
                    }
            );
        } catch (Exception e) {
            log.error("[收藏] MQ发送异常: userId={}, noteId={}, action={}", userId, noteId, action, e);
        }
    }
}
