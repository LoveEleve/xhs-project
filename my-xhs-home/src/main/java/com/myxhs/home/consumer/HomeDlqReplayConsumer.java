package com.myxhs.home.consumer;

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
 * home BFF DLQ 重放消费者（C-014 F4：2 组）
 * <p>
 * feed-push / note-delete 两消费组重试耗尽（maxReconsumeTimes=3）进死信后，重投回源 topic
 * （**保留 tag**——note-delete 消费组按 selectorExpression=NOTE_DELETE 路由，丢 tag 消息无效）。
 * </p>
 * <p>
 * 重放安全性：feed-push 幂等（ZADD 同 member 只更新 score）；note-delete 幂等
 * （outbox ZREM / following SCAN 清理 / 已删标记重设均为幂等操作）。
 * 规则同家族（C-007/8/9/10/11/12/13）：attempt≤{@link #MAX_REPLAY} 自动重放；超限仅告警防循环；重投失败不吞。
 * </p>
 */
@Slf4j
public final class HomeDlqReplayConsumer {

    /** 重放次数消息属性 Key（重投时 +1） */
    public static final String REPLAY_COUNT_PROPERTY = "dlqReplayCount";

    /** 自动重放上限：超过则仅告警（防循环；转人工/补偿兜底） */
    private static final int MAX_REPLAY = 3;

    private HomeDlqReplayConsumer() {
    }

    /** 重投一条 DLQ 消息到源 topic（保留 tag） */
    static void replay(MessageExt msg, String sourceTopic,
                       RocketMQTemplate rocketMQTemplate, BusinessMetrics businessMetrics) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            int attempt = parseReplayCount(msg);
            if (attempt >= MAX_REPLAY) {
                log.error("[homeDLQ] 重放超限({}>={})，停止自动重放(防循环)转人工/补偿: sourceTopic={}, msgId={}",
                        attempt, MAX_REPLAY, sourceTopic, msg.getMsgId());
                businessMetrics.recordAction("home", "dlq_replay_over_limit");
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
            log.warn("[homeDLQ] 已重投源 topic(第 {} 次): destination={}, msgId={}",
                    attempt + 1, destination, msg.getMsgId());
            businessMetrics.recordAction("home", "dlq_replay_ok");
        } catch (Exception e) {
            businessMetrics.recordAction("home", "dlq_replay_fail");
            log.error("[homeDLQ] 重投失败(抛出让本组重试/进本组DLQ，不静默丢消息): sourceTopic={}, msgId={}",
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

    /** Feed 推送消费组 DLQ 重放 */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%feed-push-consumer-group",
            consumerGroup = "home-dlq-replay-feed-group"
    )
    public static class FeedPushDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "FEED_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }

    /** 笔记删除消费组 DLQ 重放（SOCIAL_TOPIC，tag 保留必须——selector 路由） */
    @Component
    @RequiredArgsConstructor
    @RocketMQMessageListener(
            topic = "%DLQ%note-delete-consumer-group",
            consumerGroup = "home-dlq-replay-note-delete-group"
    )
    public static class NoteDeleteDlqReplayListener implements RocketMQListener<MessageExt> {
        private final RocketMQTemplate rocketMQTemplate;
        private final BusinessMetrics businessMetrics;

        @Override
        public void onMessage(MessageExt msg) {
            replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        }
    }
}
