package com.myxhs.notification.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知域 DLQ 重放消费者单测（C-012 F2）
 * <p>
 * 验证口径：重投目的地 / KEYS + 原 msgId 属性保留 / 计数 +1 / 超限仅告警 / 失败不吞。
 * </p>
 */
class NotificationDlqReplayConsumerTest {

    private RocketMQTemplate rocketMQTemplate;
    private BusinessMetrics businessMetrics;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        businessMetrics = mock(BusinessMetrics.class);
    }

    private static MessageExt msg(String keys, String replayCount) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setTags("INDEX");
        m.setBody("{\"userId\":9}".getBytes(StandardCharsets.UTF_8));
        if (keys != null) {
            m.setKeys(keys);
        }
        if (replayCount != null) {
            m.getProperties().put(NotificationDlqReplayConsumer.REPLAY_COUNT_PROPERTY, replayCount);
        }
        return m;
    }

    @Test
    @DisplayName("首次重放：保留 tag + 原 msgId 属性 + KEYS，计数置 1")
    void replayKeepsTagOriginalMsgIdAndKeys() {
        NotificationDlqReplayConsumer.replay(msg("ORDER-1001", null), "NOTIFICATION_TOPIC",
                rocketMQTemplate, businessMetrics);

        ArgumentCaptor<Message<String>> bodyCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("NOTIFICATION_TOPIC:INDEX"), bodyCaptor.capture());
        Message<String> sent = bodyCaptor.getValue();
        assertThat(sent.getHeaders().get(NotificationDlqReplayConsumer.REPLAY_COUNT_PROPERTY)).isEqualTo("1");
        assertThat(sent.getHeaders().get(NotificationDlqReplayConsumer.ORIGINAL_MSG_ID_PROPERTY)).isEqualTo("MSG-1");
        assertThat(sent.getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo("ORDER-1001");
        verify(businessMetrics).recordAction("notification", "dlq_replay_ok");
    }

    @Test
    @DisplayName("无 KEYS 消息：不设置 KEYS 头；目的地=纯 topic")
    void replayWithoutKeys() {
        NotificationDlqReplayConsumer.replay(msg(null, null), "ORDER_TERMINATION_TOPIC",
                rocketMQTemplate, businessMetrics);

        ArgumentCaptor<Message<String>> bodyCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("ORDER_TERMINATION_TOPIC:INDEX"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().getHeaders().get(RocketMQHeaders.KEYS)).isNull();
    }

    @Test
    @DisplayName("重放超限（attempt>=3）：不重投，仅告警指标")
    void replayOverLimitStops() {
        NotificationDlqReplayConsumer.replay(msg("ORDER-1", "3"), "NOTIFICATION_TOPIC",
                rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordAction("notification", "dlq_replay_over_limit");
    }

    @Test
    @DisplayName("重投失败：抛出让本组重试/进本组 DLQ（不静默丢消息）")
    void replayFailurePropagates() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"));

        assertThatThrownBy(() -> NotificationDlqReplayConsumer.replay(
                msg("ORDER-1", "1"), "NOTIFICATION_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DLQ 重投失败");
        verify(businessMetrics).recordAction("notification", "dlq_replay_fail");
    }
}
