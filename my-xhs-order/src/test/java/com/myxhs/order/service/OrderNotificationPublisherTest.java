package com.myxhs.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.mapper.LocalMessageMapper;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单状态通知发布器单测（C-012 F6：outbox 化）
 * <p>
 * 验证口径：事件落本地消息（operationType/status=0/payload 为事件 JSON）→ 即时发送 →
 * 成功回调 markSuccess（按行 id）→ 失败留库（补发 Job 兜底）；落库异常不阻断主链路。
 * </p>
 */
@SuppressWarnings("unchecked")
class OrderNotificationPublisherTest {

    private RocketMQTemplate rocketMQTemplate;
    private LocalMessageMapper localMessageMapper;
    private OrderNotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        localMessageMapper = mock(LocalMessageMapper.class);
        publisher = new OrderNotificationPublisher(rocketMQTemplate, new ObjectMapper(), localMessageMapper);
    }

    @Test
    @DisplayName("发布：落本地消息（ORDER_STATUS_NOTIFICATION + status=0）+ 即时发送")
    void publishPersistsAndSends() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, LocalMessage.class).setId(9001L);
            return 1;
        });

        publisher.publishStatusChanged(9L, 1001L, "ORDER-1001", "支付成功，我们将尽快为你发货", null);

        ArgumentCaptor<LocalMessage> msgCaptor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(msgCaptor.capture());
        LocalMessage saved = msgCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getTransactionId()).isEqualTo("ORDER-1001");
        assertThat(saved.getOperationType()).isEqualTo(OrderNotificationPublisher.OPERATION_TYPE_ORDER_STATUS_NOTIFICATION);
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getPayload()).contains("\"type\":5").contains("\"targetType\":3").contains("支付成功");

        ArgumentCaptor<Message<String>> sendCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSend(eq("NOTIFICATION_TOPIC"), sendCaptor.capture(), any(SendCallback.class));
        assertThat(sendCaptor.getValue().getPayload()).isEqualTo(saved.getPayload()); // 即时/补发字节一致
    }

    @Test
    @DisplayName("发送成功回调：按行 id markSuccess（同订单多状态共享 transactionId，不能粗标）")
    void successMarksById() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, LocalMessage.class).setId(9001L);
            return 1;
        });

        publisher.publishStatusChanged(9L, 1001L, "ORDER-1001", "退款已到账", null);

        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);
        verify(rocketMQTemplate).asyncSend(anyString(), any(Message.class), callbackCaptor.capture());
        callbackCaptor.getValue().onSuccess(null);

        verify(localMessageMapper).markSuccess(9001L);
    }

    @Test
    @DisplayName("发送失败回调：不标记（留库 → 补发 Job 兜底）")
    void failureLeavesRowForRetry() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, LocalMessage.class).setId(9001L);
            return 1;
        });

        publisher.publishStatusChanged(9L, 1001L, "ORDER-1001", "子订单已发货", "顺丰 SF123");

        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);
        verify(rocketMQTemplate).asyncSend(anyString(), any(Message.class), callbackCaptor.capture());
        callbackCaptor.getValue().onException(new RuntimeException("MQ down"));

        verify(localMessageMapper, never()).markSuccess(any(Long.class));
    }

    @Test
    @DisplayName("落库失败：不阻断主链路（不抛出）")
    void persistFailureDoesNotThrow() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenThrow(new RuntimeException("DB down"));

        publisher.publishStatusChanged(9L, 1001L, "ORDER-1001", "支付成功", null); // 不抛

        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class));
    }

    @Test
    @DisplayName("关键参数缺失：静默返回（不发不落）")
    void missingArgsNoop() {
        publisher.publishStatusChanged(null, 1001L, "ORDER-1001", "x", null);
        publisher.publishStatusChanged(9L, null, "ORDER-1001", "x", null);
        publisher.publishStatusChanged(9L, 1001L, null, "x", null);

        verify(localMessageMapper, never()).insert(any(LocalMessage.class));
    }
}
