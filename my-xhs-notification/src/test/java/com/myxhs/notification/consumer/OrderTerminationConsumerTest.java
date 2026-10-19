package com.myxhs.notification.consumer;

import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.service.NotificationService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单终结通知消费者单测（C-012）
 * <p>
 * 验证口径：幂等键三级解析（KEYS → 原 msgId 属性 → msgId）、载荷构建（type=5/targetType=3/原因文案）、失败删标记。
 * </p>
 */
class OrderTerminationConsumerTest {

    private static final String BIZ_TYPE = "myxhs:notification:termination-consumed";

    private NotificationService notificationService;
    private MessageIdempotentHelper idempotentHelper;
    private OrderTerminationConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        idempotentHelper = mock(MessageIdempotentHelper.class);
        consumer = new OrderTerminationConsumer(notificationService, idempotentHelper);
    }

    private static MessageExt msg(String reason) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setBody(("{\"userId\":9,\"orderId\":1001,\"orderNo\":\"ORDER-1001\",\"reason\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        m.setTags("TEST"); // setTags 会懒初始化 properties（MessageExt.getProperties 直接 new 时为 null）
        return m;
    }

    @Test
    @DisplayName("幂等键优先 KEYS（orderNo，跨重投稳定）")
    void dedupByKeys() {
        MessageExt m = msg("TIMEOUT");
        m.setKeys("ORDER-1001");
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(m);

        verify(idempotentHelper).isFirstProcess(BIZ_TYPE, "ORDER-1001", 86400L);
    }

    @Test
    @DisplayName("C-012 F2：无 KEYS 时回退原 msgId 属性（重放稳定）")
    void dedupByOriginalMsgIdWhenNoKeys() {
        MessageExt m = msg("TIMEOUT");
        m.getProperties().put(NotificationDlqReplayConsumer.ORIGINAL_MSG_ID_PROPERTY, "ORIG-1");
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(m);

        verify(idempotentHelper).isFirstProcess(BIZ_TYPE, "ORIG-1", 86400L);
    }

    @Test
    @DisplayName("无 KEYS 无属性 → 回退 broker msgId")
    void dedupByMsgIdFallback() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(msg("TIMEOUT"));

        verify(idempotentHelper).isFirstProcess(BIZ_TYPE, "MSG-1", 86400L);
    }

    @Test
    @DisplayName("载荷构建：type=5 / targetType=3 / 原因文案（TIMEOUT）")
    void payloadBuiltWithReason() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(msg("TIMEOUT"));

        ArgumentCaptor<NotificationEventDTO> captor = ArgumentCaptor.forClass(NotificationEventDTO.class);
        verify(notificationService).processEvent(captor.capture());
        NotificationEventDTO dto = captor.getValue();
        assertThat(dto.getType()).isEqualTo(5);
        assertThat(dto.getTargetType()).isEqualTo(3);
        assertThat(dto.getTargetUserId()).isEqualTo(9L);
        assertThat(dto.getContent()).contains("ORDER-1001").contains("超时未支付");
        assertThat(dto.getExtraData()).contains("TIMEOUT");
    }

    @Test
    @DisplayName("处理失败 → 删幂等标记 + 抛异常")
    void failureRemovesMarkAndThrows() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(notificationService).processEvent(any());

        assertThatThrownBy(() -> consumer.onMessage(msg("USER_CANCELLED")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("订单终结通知消费失败");
        verify(idempotentHelper).removeMark(BIZ_TYPE, "MSG-1");
    }

    @Test
    @DisplayName("缺关键字段（userId/orderId）→ 告警跳过")
    void missingFieldsSkipped() {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-2");
        m.setBody("{\"reason\":\"TIMEOUT\"}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(m);

        verify(notificationService, never()).processEvent(any());
    }
}
