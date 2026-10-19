package com.myxhs.notification.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 通知域 DLQ 重放消费者（C-012 F2：2 组）
 * <p>
 * notification-event / order-termination 两消费组重试耗尽（maxReconsumeTimes=3）进死信后，
 * 重投回源 topic（保留 tag 路由）；与 C-007/8/9/10/11 家族同构，另加**消息级幂等键保留**：
 * <ul>
 *   <li>{@link #ORIGINAL_MSG_ID_PROPERTY}：原 broker msgId——notification-event 的幂等键就是 msgId，
 *       重投换新 msgId 会绕过去重（已处理消息被重放 → 聚合计数双计）→ 保留原值供消费者去重；</li>
 *   <li>KEYS：order-termination 的业务幂等键（orderNo）——跨重投稳定（Outbox 补发同款语义）。</li>
 * </ul>
 * </p>
 * <p>
 * 规则同家族：attempt≤{@link #MAX_REPLAY} 自动重放；超限仅告警防循环；重投失败不吞。
 * </p>
 */
@Slf4j
public final class NotificationDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 原 msgId 消息属性 Key（重投时保留——消费者幂等键优先取它） */
    public static final String ORIGINAL_MSG_ID_PROPERTY = "originalMsgId";

    /** 自动重放上限：超过则仅告警（防循环；转人工/对账兜底） */
    private static final int MAX_REPLAY = 3;

    private NotificationDlqReplayConsumer() {
    }

    /** 重投一条 DLQ 消息到源 topic（保留 tag + KEYS + 原 msgId） */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[通知DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/对账: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordAction("notification", "dlq_replay_over_limit");
                return;
            }

            String destination = sourceTopic;
            if (msg.getTags() != null && !msg.getTags().isBlank()) {
                destination = sourceTopic + ":" + msg.getTags();
            }
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            var builder = MessageBuilder.withPayload(body)
                    .setHeader(REPLAY_COUNT_PROPERTY, String.valueOf(attempt + 1))
                    .setHeader(ORIGINAL_MSG_ID_PROPERTY, msg.getMsgId());
            if (msg.getKeys() != null && !msg.getKeys().isBlank()) {
                builder.setHeader(RocketMQHeaders.KEYS, msg.getKeys());
            }
            rocketMQTemplate.syncSend(destination, MqTraceHelper.wrapWithTraceId(builder.build()));
            log.warn("[通知DLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordAction("notification", "dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordAction("notification", "dlq_replay_fail");
            log.error("[通知DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
                    sourceTopic, msg.getMsgId(), e);
            throw new RuntimeException("DLQ 重投失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private static int parseReplayCount(MessageExt msg) {
        String value = msg.getProperty(REPLAY_COUNT_PROPERTY);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 通知事件消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%notification-event-consumer-group",
            consumerGroup = "notification-dlq-replay-event-group"
    )
    public static class EventDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "NOTIFICATION_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 订单终结通知消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%notification-order-termination-consumer-group",
            consumerGroup = "notification-dlq-replay-termination-group"
    )
    public static class OrderTerminationDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "ORDER_TERMINATION_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
