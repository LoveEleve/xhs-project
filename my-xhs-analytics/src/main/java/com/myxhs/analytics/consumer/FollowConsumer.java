package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.FollowEvent;
import com.myxhs.analytics.entity.Follow;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
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
            FollowEvent event = objectMapper.readValue(message, FollowEvent.class);

            // 落库 MySQL
            Follow follow = new Follow();
            follow.setId(idGeneratorUtil.nextId());
            follow.setUserId(event.getUserId());
            follow.setFollowUserId(event.getTargetUserId());
            follow.setCreatedAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getActionTime()), ZoneId.systemDefault()));

            try {
                followMapper.insert(follow);
                log.info("[关注Consumer] 落库成功: userId={}, targetUserId={}",
                        event.getUserId(), event.getTargetUserId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 唯一索引冲突 = 重复消费，幂等忽略
                log.debug("[关注Consumer] 重复消费忽略: userId={}, targetUserId={}",
                        event.getUserId(), event.getTargetUserId());
            }
        } catch (Exception e) {
            log.error("[关注Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("关注消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
