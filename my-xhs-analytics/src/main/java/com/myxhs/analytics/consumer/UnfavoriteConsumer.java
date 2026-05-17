package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.FavoriteEvent;
import com.myxhs.analytics.mapper.FavoriteMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 取消收藏事件 MQ 消费者 — 异步删除 MySQL 记录
 * <p>
 * 消费 SOCIAL_TOPIC:UNFAVORITE 消息，从 t_favorite 表删除收藏记录。
 * 删除操作天然幂等。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "UNFAVORITE",
        consumerGroup = "unfavorite-consumer-group"
)
public class UnfavoriteConsumer implements RocketMQListener<MessageExt> {

    private final FavoriteMapper favoriteMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            FavoriteEvent event = objectMapper.readValue(message, FavoriteEvent.class);

            // 删除 MySQL 记录（DELETE 天然幂等）
            int deleted = favoriteMapper.deleteByUserAndNote(event.getUserId(), event.getNoteId());
            log.info("[取消收藏Consumer] 删除记录: userId={}, noteId={}, deleted={}",
                    event.getUserId(), event.getNoteId(), deleted);
        } catch (Exception e) {
            log.error("[取消收藏Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("取消收藏消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
