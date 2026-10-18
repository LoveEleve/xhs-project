package com.myxhs.coupon.consumer;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CouponDlqReplayConsumer 单测（C-007）
 */
@ExtendWith(MockitoExtension.class)
class CouponDlqReplayConsumerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private BusinessMetrics businessMetrics;

    @Test
    @DisplayName("C-007：DLQ 消息重投源 topic（attempt 属性 +1 透传）")
    void replayResendsToSourceTopic() {
        MessageExt msg = message("dlq-1", "{\"userId\":1001}", null);

        CouponDlqReplayConsumer.replay(msg, "COUPON_CLAIM_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("COUPON_CLAIM_TOPIC"), captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"userId\":1001}");
        assertThat(captor.getValue().getHeaders().get(CouponDlqReplayConsumer.REPLAY_COUNT_PROPERTY))
                .isEqualTo("1");
        verify(businessMetrics).recordCouponAction("dlq_replay_ok", true);
    }

    @Test
    @DisplayName("C-007：已重放 2 次 → 第 3 次重投（属性累加为 3）")
    void replayIncrementsAttempt() {
        MessageExt msg = message("dlq-2", "{}", "2");

        CouponDlqReplayConsumer.replay(msg, "ORDER_TERMINATION_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("ORDER_TERMINATION_TOPIC"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(CouponDlqReplayConsumer.REPLAY_COUNT_PROPERTY))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("C-007：重放超限（attempt=3）→ 仅告警不重投（防循环）")
    void replayStopsAtMaxAttempts() {
        MessageExt msg = message("dlq-3", "{}", "3");

        CouponDlqReplayConsumer.replay(msg, "COUPON_CLAIM_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordCouponAction("dlq_replay_over_limit", false);
    }

    @Test
    @DisplayName("C-007：重投失败 → 抛异常不吞（防静默丢消息）+ 指标 fail")
    void replaySendFailureThrows() {
        MessageExt msg = message("dlq-4", "{}", null);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> CouponDlqReplayConsumer.replay(
                msg, "COUPON_CLAIM_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DLQ 重投失败");
        verify(businessMetrics).recordCouponAction("dlq_replay_fail", false);
    }

    @Test
    @DisplayName("C-007：非法 attempt 属性（非数字）→ 按首次（0）处理")
    void replayInvalidAttemptTreatedAsZero() {
        MessageExt msg = message("dlq-5", "{}", "not-a-number");

        CouponDlqReplayConsumer.replay(msg, "COUPON_CLAIM_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("COUPON_CLAIM_TOPIC"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(CouponDlqReplayConsumer.REPLAY_COUNT_PROPERTY))
                .isEqualTo("1");
    }

    private MessageExt message(String msgId, String body, String replayCount) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        if (replayCount != null) {
            message.putUserProperty(CouponDlqReplayConsumer.REPLAY_COUNT_PROPERTY, replayCount);
        }
        return message;
    }
}
