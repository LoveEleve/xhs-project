package com.myxhs.search.consumer;

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
 * 搜索域 DLQ 重放消费者（C-011 R7：5 组）
 * <p>
 * note-index-sync / product-index-sync / counter-es-sync / recommend-behavior / seller-sub-order-index-sync
 * 五个消费组重试耗尽进死信后，重投回源 topic（**保留原 tag**——SOCIAL_TOPIC 组按 selectorExpression 路由），
 * 由正常消费者再处理（ExternalGte 版本幂等 / INSERT IGNORE 幂等保护）。
 * </p>
 * <p>
 * 规则同 C-007/8/9/10 家族：attempt≤{@link #MAX_REPLAY} 自动重放；超限仅告警防循环；重投失败不吞。
 * </p>
 */
@Slf4j
public final class SearchDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/补偿任务兜底） */
    private static final int MAX_REPLAY = 3;

    private SearchDlqReplayConsumer() {
    }

    /** 重投一条 DLQ 消息到源 topic（保留 tag） */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[搜索DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/补偿: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordAction("search", "dlq_replay_over_limit");
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
            log.warn("[搜索DLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordAction("search", "dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordAction("search", "dlq_replay_fail");
            log.error("[搜索DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** 笔记索引同步消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%note-index-sync-consumer-group",
            consumerGroup = "search-dlq-replay-note-group"
    )
    public static class NoteIndexDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "NOTE_INDEX_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 商品索引同步消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%product-index-sync-consumer-group",
            consumerGroup = "search-dlq-replay-product-group"
    )
    public static class ProductIndexDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "PRODUCT_INDEX_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 点赞计数同步消费组 DLQ 重放（SOCIAL_TOPIC，保留 tag） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%counter-es-sync-consumer-group",
            consumerGroup = "search-dlq-replay-counter-es-group"
    )
    public static class CounterEsDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 推荐行为上报消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%recommend-behavior-consumer-group",
            consumerGroup = "search-dlq-replay-behavior-group"
    )
    public static class BehaviorDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "RECOMMEND_BEHAVIOR_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 商家子单索引同步消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%seller-sub-order-index-sync-group",
            consumerGroup = "search-dlq-replay-seller-order-group"
    )
    public static class SellerOrderDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SUB_ORDER_INDEX_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
