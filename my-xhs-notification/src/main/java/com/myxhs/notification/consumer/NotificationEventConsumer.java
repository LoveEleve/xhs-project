package com.myxhs.notification.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通知事件消费者
 * <p>
 * 消费 NOTIFICATION_TOPIC 中的事件消息，生成通知并推送。
 * </p>
 * <p>
 * 幂等保证（两级）：
 * 1. Redis 快速去重（辅助，24h 过期）—— 快速拦截重复消息
 * 2. 聚合器中的 SETNX 窗口锁 —— 保证同一窗口内不重复创建
 * </p>
 * <p>
 * 注意：不依赖 DB 唯一键做幂等（通知表没有天然唯一键），
 * 而是通过 Redis msgId 去重 + 聚合窗口锁双重保障。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "NOTIFICATION_TOPIC",
        consumerGroup = "notification-event-consumer-group"
)
public class NotificationEventConsumer implements RocketMQListener<MessageExt> {

    private final NotificationService notificationService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String CONSUMED_KEY_PREFIX = "notify:consumed:";

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String msgId = msg.getMsgId();
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);

            // 一级幂等：Redis 快速去重（先检查是否已消费）
            String consumedKey = CONSUMED_KEY_PREFIX + msgId;
            Boolean alreadyConsumed = stringRedisTemplate.hasKey(consumedKey);
            if (Boolean.TRUE.equals(alreadyConsumed)) {
                log.info("[通知消费] Redis去重跳过: msgId={}", msgId);
                return;
            }

            // 解析事件
            NotificationEventDTO event = JSON.parseObject(body, NotificationEventDTO.class);
            if (event == null || event.getTargetUserId() == null) {
                log.warn("[通知消费] 消息体解析失败或缺少targetUserId: msgId={}", msgId);
                return;
            }

            // 不给自己发通知（自己赞自己的笔记等）
            if (event.getSenderId() != null && event.getSenderId().equals(event.getTargetUserId())) {
                log.debug("[通知消费] 跳过自己给自己的通知: senderId={}", event.getSenderId());
                return;
            }

            // 处理通知事件
            notificationService.processEvent(event);

            // 消费成功后才写入去重 Key（失败时不写入，允许 RocketMQ 重试）
            // 注意：这里存在极小概率的重复消费（processEvent 成功但 Redis SET 失败），
            // 由聚合器的 SETNX 窗口锁兜底保证幂等。
            stringRedisTemplate.opsForValue().set(consumedKey, "1", Duration.ofHours(24));

        } catch (Exception e) {
            log.error("[通知消费] 处理失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("通知消费失败", e); // 触发 RocketMQ 重试
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
