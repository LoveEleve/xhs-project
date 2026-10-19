package com.myxhs.inventory.consumer;

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
 * 库存 DLQ 重放消费者（C-008 F8）
 * <p>
 * 为什么需要它？消费失败重试耗尽后消息进入死信队列（%DLQ%&lt;consumerGroup&gt;），此前无任何兜底——
 * 最险的是预扣消息进 DLQ：订单已建、库存永不扣（超卖方向且无自动检出）；
 * L2 落账进 DLQ：MySQL 镜像永久滞后（对账可愈但窗口长）。
 * </p>
 * <p>
 * 机制：把 DLQ 消息重投回源 topic，由正常消费者再处理（消费端幂等保护：inbox/完成态标记/Lua 幂等）：
 * - 重放次数记在消息属性 {@link #REPLAY_COUNT_PROPERTY}（重投时 +1），≤{@link #MAX_REPLAY} 自动重放；
 * - 超限（数据损坏等确定性失败）仅告警 + 指标，ack 丢弃防"DLQ→重放→再 DLQ"无限循环；
 * - 重投失败不吞异常（抛出让本组重试/进本组 DLQ，可见、防静默丢消息）；
 * - 保留原消息的 tag（INVENTORY_TOPIC:ACTION 订阅按 tag 路由）与 KEYS（终止消费组按 KEYS=orderNo 幂等）。
 * </p>
 */
@Slf4j
public final class InventoryDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/对账兜底） */
    private static final int MAX_REPLAY = 3;

    private InventoryDlqReplayConsumer() {
    }

    /**
     * 重投一条 DLQ 消息到源 topic（三个监听器共用）
     *
     * @param sourceTopic 源 topic（deduct 组会拼接原 tag）
     */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[库存DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/对账: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordInventoryAction("dlq_replay_over_limit");
                return;
            }

            // 保留原 tag（INVENTORY_TOPIC 按 action tag 订阅）
            String destination = sourceTopic;
            if (msg.getTags() != null && !msg.getTags().isBlank()) {
                destination = sourceTopic + ":" + msg.getTags();
            }

            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            MessageBuilder<String> builder = MessageBuilder.withPayload(body)
                    .setHeader(REPLAY_COUNT_PROPERTY, String.valueOf(attempt + 1));
            // 保留原 KEYS（终止消费组按 KEYS=orderNo 幂等，重放换新 msgId 时会退化为 msgId）
            if (msg.getKeys() != null && !msg.getKeys().isBlank()) {
                builder.setHeader(RocketMQHeaders.KEYS, msg.getKeys());
            }
            rocketMQTemplate.syncSend(destination, MqTraceHelper.wrapWithTraceId(builder.build()));
            log.warn("[库存DLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordInventoryAction("dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordInventoryAction("dlq_replay_fail");
            log.error("[库存DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** 预扣入口消费组 DLQ 重放（ORDER_TRANSACTION_TOPIC） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%inventory-order-transaction-consumer-group",
            consumerGroup = "inventory-dlq-replay-transaction-group"
    )
    public static class OrderTransactionDlqReplayListener implements RocketMQListener<MessageExt> {

        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "ORDER_TRANSACTION_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** L2 落账消费组 DLQ 重放（INVENTORY_TOPIC，保留 action tag） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%inventory-deduct-consumer-group",
            consumerGroup = "inventory-dlq-replay-deduct-group"
    )
    public static class DeductDlqReplayListener implements RocketMQListener<MessageExt> {

        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "INVENTORY_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 订单终结消费组 DLQ 重放（ORDER_TERMINATION_TOPIC，保留 KEYS） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%inventory-order-termination-consumer-group",
            consumerGroup = "inventory-dlq-replay-termination-group"
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
