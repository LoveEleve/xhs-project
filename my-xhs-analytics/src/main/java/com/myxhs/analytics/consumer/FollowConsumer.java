package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.entity.Follow;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.trace.MqTraceHelper;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 关注事件 MQ 消费者 — 异步落库 MySQL
 * <p>
 * 消费 SOCIAL_TOPIC:FOLLOW 消息，将关注记录写入 t_follow 表。
 * </p>
 * <p>
 * 注意：FollowService 当前使用同步方式落库 MySQL（Redis Lua + 同步 MySQL），
 * 同时发送 MQ 消息（SOCIAL_TOPIC:FOLLOW/UNFOLLOW）供 counter 模块计数更新。
 * 此消费者默认禁用——如果启用，会与 FollowService 的同步 MySQL 落库产生重复插入
 * （唯一索引保证幂等，但会产生不必要的 DuplicateKeyException 日志）。
 * 如需启用请设置 myxhs.mq.follow-consumer.enabled=true。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "myxhs.mq.follow-consumer.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "FOLLOW",
        consumerGroup = "follow-consumer-group",
        maxReconsumeTimes = 3
)
public class FollowConsumer implements RocketMQListener<MessageExt> {

    private final FollowMapper followMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = objectMapper.readValue(message, Map.class);

            Long followerUserId = toLong(eventMap.get("followerUserId"));
            Long followeeUserId = toLong(eventMap.get("followeeUserId"));
            if (followerUserId == null || followeeUserId == null) {
                log.warn("[关注Consumer] 缺少必填字段: follower={}, followee={}", followerUserId, followeeUserId);
                return;
            }

            // 落库 MySQL
            Follow follow = new Follow();
            follow.setId(idGeneratorUtil.nextId());
            follow.setUserId(followerUserId);
            follow.setFollowUserId(followeeUserId);
            follow.setCreatedAt(LocalDateTime.now());

            try {
                followMapper.insert(follow);
                log.info("[关注Consumer] 落库成功: userId={}, targetUserId={}",
                        followerUserId, followeeUserId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.debug("[关注Consumer] 重复消费忽略: userId={}, targetUserId={}",
                        followerUserId, followeeUserId);
            }
        } catch (Exception e) {
            log.error("[关注Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("关注消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
