package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.FavoriteEvent;
import com.myxhs.analytics.entity.Favorite;
import com.myxhs.analytics.mapper.FavoriteMapper;
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
 * 收藏事件 MQ 消费者 — 异步落库 MySQL
 * <p>
 * 消费 SOCIAL_TOPIC:FAVORITE 消息，将收藏记录写入 t_favorite 表。
 * MySQL 唯一索引 uk_user_note 保证幂等。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "FAVORITE",
        consumerGroup = "favorite-consumer-group",
        maxReconsumeTimes = 3
)
public class FavoriteConsumer implements RocketMQListener<MessageExt> {

    private final FavoriteMapper favoriteMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            FavoriteEvent event = objectMapper.readValue(message, FavoriteEvent.class);

            // 落库 MySQL（唯一索引 uk_user_note 保证幂等）
            Favorite favorite = new Favorite();
            favorite.setId(idGeneratorUtil.nextId());
            favorite.setUserId(event.getUserId());
            favorite.setNoteId(event.getNoteId());
            favorite.setCreatedAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getTimestamp()), ZoneId.systemDefault()));

            try {
                favoriteMapper.insert(favorite);
                log.info("[收藏Consumer] 落库成功: userId={}, noteId={}", event.getUserId(), event.getNoteId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 唯一索引冲突 = 重复消费，幂等忽略
                log.debug("[收藏Consumer] 重复消费忽略: userId={}, noteId={}", event.getUserId(), event.getNoteId());
            }
        } catch (Exception e) {
            log.error("[收藏Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("收藏消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
