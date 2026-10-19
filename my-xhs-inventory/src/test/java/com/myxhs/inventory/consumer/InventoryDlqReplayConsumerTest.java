package com.myxhs.inventory.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
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
 * 库存 DLQ 重放消费者单测（C-008 F8）
 */
@ExtendWith(MockitoExtension.class)
class InventoryDlqReplayConsumerTest {

    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private BusinessMetrics businessMetrics;

    @Test
    @DisplayName("C-008：DLQ 消息重投源 topic（attempt 属性 +1、保留 action tag）")
    void replayResendsWithTag() {
        MessageExt msg = message("dlq-1", "{\"orderId\":1}", "CONFIRM", null);

        InventoryDlqReplayConsumer.replay(msg, "INVENTORY_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("INVENTORY_TOPIC:CONFIRM"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(InventoryDlqReplayConsumer.REPLAY_COUNT_PROPERTY))
                .isEqualTo("1");
        verify(businessMetrics).recordInventoryAction("dlq_replay_ok");
    }

    @Test
    @DisplayName("C-008：保留原 KEYS（终止消费组按 KEYS=orderNo 幂等）")
    void replayPreservesKeys() {
        MessageExt msg = message("dlq-2", "{\"orderNo\":\"ORD-1\"}", null, "ORD-1");

        InventoryDlqReplayConsumer.replay(msg, "ORDER_TERMINATION_TOPIC", rocketMQTemplate, businessMetrics);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("ORDER_TERMINATION_TOPIC"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("C-008：重放超限（attempt=3）→ 仅告警不重投（防循环）")
    void replayStopsAtMaxAttempts() {
        MessageExt msg = message("dlq-3", "{}", null, null);
        msg.putUserProperty(InventoryDlqReplayConsumer.REPLAY_COUNT_PROPERTY, "3");

        InventoryDlqReplayConsumer.replay(msg, "ORDER_TRANSACTION_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordInventoryAction("dlq_replay_over_limit");
    }

    @Test
    @DisplayName("C-008：重投失败 → 抛异常不吞（防静默丢消息）+ 指标 fail")
    void replaySendFailureThrows() {
        MessageExt msg = message("dlq-4", "{}", null, null);
        when(rocketMQTemplate.syncSend(eq("ORDER_TRANSACTION_TOPIC"), any(Message.class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> InventoryDlqReplayConsumer.replay(
                msg, "ORDER_TRANSACTION_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DLQ 重投失败");
        verify(businessMetrics).recordInventoryAction("dlq_replay_fail");
    }

    private MessageExt message(String msgId, String body, String tags, String keys) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        if (tags != null) {
            message.setTags(tags);
        }
        if (keys != null) {
            message.setKeys(keys);
        }
        return message;
    }
}
