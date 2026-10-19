package com.myxhs.counter.consumer;

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
 * 计数消费 DLQ 重放消费者（C-009 D4）
 * <p>
 * 消费失败重试耗尽后消息进入死信队列（%DLQ%&lt;consumerGroup&gt;），此前无任何兜底。
 * 机制：重投回源 topic（SOCIAL_TOPIC，**保留原 tag**——消费者按 tag 路由），
 * 由正常消费者再处理（版本门完成态 + set-based/dedup 幂等保护）。
 * </p>
 * <p>
 * - 重放次数记在消息属性 {@link #REPLAY_COUNT_PROPERTY}（重投时 +1），≤{@link #MAX_REPLAY} 自动重放；
 * - 超限（数据损坏等确定性失败）仅告警 + 指标，ack 丢弃防"DLQ→重放→再 DLQ"无限循环；
 * - 重投失败不吞异常（抛出让本组重试/进本组 DLQ，可见、防静默丢消息）。
 * </p>
 */
@Slf4j
public final class CounterDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/对账兜底） */
    private static final int MAX_REPLAY = 3;

    private CounterDlqReplayConsumer() {
    }

    /** 重投一条 DLQ 消息到源 topic（保留 tag） */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[计数DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/对账: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordAction("counter", "dlq_replay_over_limit");
                return;
            }

            String destination = sourceTopic;
            if (msg.getTags() != null && !msg.getTags().isBlank()) {
                destination = sourceTopic + ":" + msg.getTags();
            }
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            rocketMQTemplate.syncSend(destination, MqTraceHelper.wrapWithTraceId(
                    MessageBuilder.withPayload(body)
                            .setHeader(REPLAY_COUNT_PROPERTY, String.valueOf(attempt + 1))
                            .build()));
            log.warn("[计数DLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordAction("counter", "dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordAction("counter", "dlq_replay_fail");
            log.error("[计数DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** 计数消费组 DLQ 重放（SOCIAL_TOPIC，保留 tag） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%counter-consumer-group",
            consumerGroup = "counter-dlq-replay-group"
    )
    public static class CounterDlqReplayListener implements RocketMQListener<MessageExt> {

        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
