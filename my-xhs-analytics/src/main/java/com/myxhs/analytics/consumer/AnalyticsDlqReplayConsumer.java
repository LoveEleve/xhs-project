package com.myxhs.analytics.consumer;

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
 * 社交分析消费 DLQ 重放消费者（C-009 D4：4 组）
 * <p>
 * like-unlike / favorite-unlike / follow / unfollow 四个消费组重试耗尽进死信后，
 * 重投回 SOCIAL_TOPIC（**保留原 tag**——消费者按 selectorExpression 路由），
 * 由正常消费者再处理（完成态版本门 + uk/DELETE 幂等保护）。
 * </p>
 * <p>
 * 规则同 C-007/8/9 家族：attempt≤{@link #MAX_REPLAY} 自动重放；超限仅告警防循环；重投失败不吞。
 * 注：follow/unfollow 消费组默认禁用（双写者）——启用后重放自动生效。
 * </p>
 */
@Slf4j
public final class AnalyticsDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/对账兜底） */
    private static final int MAX_REPLAY = 3;

    private AnalyticsDlqReplayConsumer() {
    }

    /** 重投一条 DLQ 消息到源 topic（保留 tag） */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[分析DLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/对账: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordAction("analytics", "dlq_replay_over_limit");
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
            log.warn("[分析DLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordAction("analytics", "dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordAction("analytics", "dlq_replay_fail");
            log.error("[分析DLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** 点赞/取消点赞消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%like-unlike-consumer-group",
            consumerGroup = "analytics-dlq-replay-like-group"
    )
    public static class LikeUnlikeDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 收藏/取消收藏消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%favorite-unlike-consumer-group",
            consumerGroup = "analytics-dlq-replay-favorite-group"
    )
    public static class FavoriteUnlikeDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 关注消费组 DLQ 重放（默认禁用——启用后自动生效） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%follow-consumer-group",
            consumerGroup = "analytics-dlq-replay-follow-group"
    )
    public static class FollowDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 取关消费组 DLQ 重放（默认禁用——启用后自动生效） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%unfollow-consumer-group",
            consumerGroup = "analytics-dlq-replay-unfollow-group"
    )
    public static class UnfollowDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
