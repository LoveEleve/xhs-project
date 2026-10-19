package com.myxhs.counter.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 计数 DLQ 重放消费者单测（C-009 D4）
 */
@ExtendWith(MockitoExtension.class)
class CounterDlqReplayConsumerTest {

    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private BusinessMetrics businessMetrics;

    @Test
    @DisplayName("C-009：DLQ 消息重投源 topic（attempt 属性 +1、保留 tag）")
    void replayResendsWithTag() {
        MessageExt msg = message("dlq-1", "{\"bizType\":1}", "LIKE");

        CounterDlqReplayConsumer.replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("SOCIAL_TOPIC:LIKE"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(CounterDlqReplayConsumer.REPLAY_COUNT_PROPERTY))
                .isEqualTo("1");
        verify(businessMetrics).recordAction("counter", "dlq_replay_ok");
    }

    @Test
    @DisplayName("C-009：重放超限（attempt=3）→ 仅告警不重投（防循环）")
    void replayStopsAtMaxAttempts() {
        MessageExt msg = message("dlq-3", "{}", null);
        msg.putUserProperty(CounterDlqReplayConsumer.REPLAY_COUNT_PROPERTY, "3");

        CounterDlqReplayConsumer.replay(msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordAction("counter", "dlq_replay_over_limit");
    }

    @Test
    @DisplayName("C-009：重投失败 → 抛异常不吞 + 指标 fail")
    void replaySendFailureThrows() {
        MessageExt msg = message("dlq-4", "{}", null);
        when(rocketMQTemplate.syncSend(eq("SOCIAL_TOPIC"), any(Message.class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> CounterDlqReplayConsumer.replay(
                msg, "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DLQ 重投失败");
        verify(businessMetrics).recordAction("counter", "dlq_replay_fail");
    }

    private MessageExt message(String msgId, String body, String tags) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        if (tags != null) {
            message.setTags(tags);
        }
        return message;
    }
}
