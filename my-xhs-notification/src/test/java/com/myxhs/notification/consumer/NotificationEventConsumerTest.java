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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知事件消费者单测（C-012）
 * <p>
 * 验证口径：两级幂等（msgId/原 msgId 属性）、自己给自己跳过、失败删标记重试（F3 契约）。
 * </p>
 */
class NotificationEventConsumerTest {

    private static final String BIZ_TYPE = "myxhs:notification:consumed";

    private NotificationService notificationService;
    private MessageIdempotentHelper idempotentHelper;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        idempotentHelper = mock(MessageIdempotentHelper.class);
        consumer = new NotificationEventConsumer(notificationService, idempotentHelper);
    }

    private static MessageExt msg(String json) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setBody(json.getBytes(StandardCharsets.UTF_8));
        m.setTags("TEST"); // setTags 会懒初始化 properties（MessageExt.getProperties 直接 new 时为 null）
        return m;
    }

    private static String eventJson(long sender, long target) {
        return "{\"type\":1,\"senderId\":" + sender + ",\"targetUserId\":" + target
                + ",\"targetId\":100,\"targetType\":1}";
    }

    @Test
    @DisplayName("首次处理：msgId 幂等通过 → 交给 service")
    void firstProcess() {
        when(idempotentHelper.isFirstProcess(eq(BIZ_TYPE), eq("MSG-1"), anyLong())).thenReturn(true);

        consumer.onMessage(msg(eventJson(7, 9)));

        ArgumentCaptor<NotificationEventDTO> captor = ArgumentCaptor.forClass(NotificationEventDTO.class);
        verify(notificationService).processEvent(captor.capture());
        assertThat(captor.getValue().getTargetUserId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("重复消息：幂等拦截 → 不处理")
    void duplicateSkipped() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(false);

        consumer.onMessage(msg(eventJson(7, 9)));

        verify(notificationService, never()).processEvent(any());
    }

    @Test
    @DisplayName("C-012 F2：重放消息带原 msgId 属性 → 幂等键取原值（重放已处理消息被去重）")
    void replayUsesOriginalMsgId() {
        MessageExt m = msg(eventJson(7, 9));
        m.getProperties().put(NotificationDlqReplayConsumer.ORIGINAL_MSG_ID_PROPERTY, "ORIG-99");
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(m);

        verify(idempotentHelper).isFirstProcess(BIZ_TYPE, "ORIG-99", 86400L);
    }

    @Test
    @DisplayName("自己给自己（senderId == targetUserId）→ 跳过")
    void selfNotificationSkipped() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(msg(eventJson(7, 7)));

        verify(notificationService, never()).processEvent(any());
    }

    @Test
    @DisplayName("处理失败 → 删幂等标记 + 抛异常（触发 MQ 重试）")
    void failureRemovesMarkAndThrows() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(notificationService).processEvent(any());

        assertThatThrownBy(() -> consumer.onMessage(msg(eventJson(7, 9))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("通知消费失败");
        verify(idempotentHelper).removeMark(BIZ_TYPE, "MSG-1");
    }

    @Test
    @DisplayName("消息缺 targetUserId → 告警跳过（不处理不抛）")
    void missingTargetUserSkipped() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        consumer.onMessage(msg("{\"type\":1,\"senderId\":7}"));

        verify(notificationService, never()).processEvent(any());
    }
}
