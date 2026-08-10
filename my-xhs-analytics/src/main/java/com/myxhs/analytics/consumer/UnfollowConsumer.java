package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 取消关注事件 MQ 消费者 — 异步删除 MySQL 记录
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "myxhs.mq.unfollow-consumer.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "UNFOLLOW",
        consumerGroup = "unfollow-consumer-group",
        maxReconsumeTimes = 3
)
public class UnfollowConsumer implements RocketMQListener<MessageExt> {

    private final FollowMapper followMapper;
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
            if (followerUserId == null || followeeUserId == null) return;

            int deleted = followMapper.deleteByUserIdAndFollowUserId(followerUserId, followeeUserId);
            log.info("[取消关注Consumer] 删除记录: userId={}, targetUserId={}, deleted={}",
                    followerUserId, followeeUserId, deleted);
        } catch (Exception e) {
            log.error("[取消关注Consumer] 消费失败", e);
            throw new RuntimeException("取消关注消息消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
