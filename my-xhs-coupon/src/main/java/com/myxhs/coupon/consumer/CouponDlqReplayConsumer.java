package com.myxhs.coupon.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 消费 DLQ 重放消费者（C-007）
 * <p>
 * 为什么需要它？消费失败重试耗尽后消息进入死信队列（%DLQ%&lt;consumerGroup&gt;），
 * 此前无任何兜底——领券消息一旦入 DLQ：Outbox 已被标记已发（SEND_OK），
 * 补发任务不再投递 → **用户扣了名额却永远拿不到券**（Design B"券必达"承诺被打破）。
 * </p>
 * <p>
 * 机制：把 DLQ 消息重投回源 topic，由正常消费者（幂等保护）再处理：
 * - 重放次数记在消息属性 {@link #REPLAY_COUNT_PROPERTY}（重投时 +1），≤{@link #MAX_REPLAY} 自动重放；
 * - 超限（数据损坏等确定性失败）仅告警 + 指标，ack 丢弃防"DLQ→重放→再 DLQ"无限循环；
 * - 重投失败不吞异常（抛出让本组重试/进本组 DLQ，可见、防静默丢消息）。
 * </p>
 * <p>
 * 与 AI 线 HITL 工具（dlq.redeliver）的关系：本类是核心线的自动兜底；人工排查仍可走 HITL 工具。
 * </p>
 */
@Slf4j
public final class CouponDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/对账兜底） */
    private static final int MAX_REPLAY = 3;

    private CouponDlqReplayConsumer() {
    }

    /**
     * 重投一条 DLQ 消息到源 topic（两个监听器共用）
     */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[优惠券DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/对账: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordCouponAction("dlq_replay_over_limit", false);
                return;
            }

            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            rocketMQTemplate.syncSend(sourceTopic, MqTraceHelper.wrapWithTraceId(
                    MessageBuilder.withPayload(body)
                            .setHeader(REPLAY_COUNT_PROPERTY, String.valueOf(attempt + 1))
                            .build()));
            log.warn("[优惠券DLQ] 已重投源 topic(第 {} 次): sourceTopic={}, msgId={}",
                    attempt + 1, sourceTopic, msg.getMsgId());
            businessMetrics.recordCouponAction("dlq_replay_ok", true);
        } catch (Exception e) {
            businessMetrics.recordCouponAction("dlq_replay_fail", false);
            log.error("[优惠券DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** 领券消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%coupon-claim-consumer-group",
            consumerGroup = "coupon-dlq-replay-claim-group"
    )
    public static class ClaimDlqReplayListener implements RocketMQListener<MessageExt> {

        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "COUPON_CLAIM_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 订单终止消费组 DLQ 重放（退券消息） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%coupon-order-termination-consumer-group",
            consumerGroup = "coupon-dlq-replay-termination-group"
    )
    public static class TerminationDlqReplayListener implements RocketMQListener<MessageExt> {

        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "ORDER_TERMINATION_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
