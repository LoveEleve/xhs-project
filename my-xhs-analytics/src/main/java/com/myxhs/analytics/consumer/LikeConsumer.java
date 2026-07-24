package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.LikeEvent;
import com.myxhs.analytics.entity.Like;
import com.myxhs.analytics.mapper.LikeMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 点赞事件 MQ 消费者 — 异步落库 MySQL
 * <p>
 * 消费 SOCIAL_TOPIC:LIKE 消息，将点赞记录写入 t_like 表。
 * MySQL 唯一索引 uk_user_biz 保证幂等（重复消费不会产生脏数据）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "LIKE",
        consumerGroup = "like-consumer-group",
        maxReconsumeTimes = 3
)
public class LikeConsumer implements RocketMQListener<MessageExt> {

    private final LikeMapper likeMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        // 恢复 TraceId（从 MQ 消息 Header 中提取，注入 MDC）
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            LikeEvent event = objectMapper.readValue(message, LikeEvent.class);

            // 落库 MySQL（唯一索引 uk_user_biz 保证幂等）
            Like like = new Like();
            like.setId(idGeneratorUtil.nextId());
            like.setUserId(event.getUserId());
            like.setBizType(event.getBizType());
            like.setBizId(event.getBizId());
            if (event.getActionTime() != null) {
                like.setCreatedAt(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.getActionTime()),
                    java.time.ZoneId.systemDefault()));
            } else {
                like.setCreatedAt(LocalDateTime.now());
            }

            try {
                likeMapper.insert(like);
                log.info("[点赞Consumer] 落库成功: userId={}, bizType={}, bizId={}",
                        event.getUserId(), event.getBizType(), event.getBizId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 唯一索引冲突 = 重复消费，幂等忽略
                log.debug("[点赞Consumer] 重复消费忽略: userId={}, bizType={}, bizId={}",
                        event.getUserId(), event.getBizType(), event.getBizId());
            }
        } catch (Exception e) {
            log.error("[点赞Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("点赞消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
