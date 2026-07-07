package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.LikeEvent;
import com.myxhs.analytics.mapper.LikeMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 取消点赞事件 MQ 消费者 — 异步删除 MySQL 记录
 * <p>
 * 消费 SOCIAL_TOPIC:UNLIKE 消息，从 t_like 表删除点赞记录。
 * 删除操作天然幂等（DELETE 不存在的记录不会报错）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "UNLIKE",
        consumerGroup = "unlike-consumer-group",
        maxReconsumeTimes = 3
)
public class UnlikeConsumer implements RocketMQListener<MessageExt> {

    private final LikeMapper likeMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            LikeEvent event = objectMapper.readValue(message, LikeEvent.class);

            // 删除 MySQL 记录（DELETE 天然幂等）
            int deleted = likeMapper.deleteByUserAndBiz(event.getUserId(), event.getBizType(), event.getBizId());
            log.info("[取消点赞Consumer] 删除记录: userId={}, bizType={}, bizId={}, deleted={}",
                    event.getUserId(), event.getBizType(), event.getBizId(), deleted);
        } catch (Exception e) {
            log.error("[取消点赞Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("取消点赞消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
