package com.myxhs.notification.service;

import com.myxhs.common.exception.BizException;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.dto.NotificationVO;
import com.myxhs.notification.dto.UnreadCountVO;
import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.mapper.PushTemplateMapper;
import com.myxhs.notification.sse.SseEmitterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知服务单测（C-012）
 * <p>
 * 验证口径：新建 +1 / 聚合不 +1 / post-commit 失败降级不抛出（F3）/ 已读幂等与守卫。
 * </p>
 */
class NotificationServiceTest {

    private NotificationMapper notificationMapper;
    private PushTemplateMapper pushTemplateMapper;
    private NotificationAggregator aggregator;
    private UnreadCountService unreadCountService;
    private SseEmitterManager sseEmitterManager;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        pushTemplateMapper = mock(PushTemplateMapper.class);
        aggregator = mock(NotificationAggregator.class);
        unreadCountService = mock(UnreadCountService.class);
        sseEmitterManager = mock(SseEmitterManager.class);
        service = new NotificationService(notificationMapper, pushTemplateMapper,
                aggregator, unreadCountService, sseEmitterManager);
    }

    private static NotificationEventDTO event() {
        return NotificationEventDTO.builder()
                .type(1).senderId(7L).senderName("小马").targetUserId(9L)
                .targetId(100L).targetType(1).content("赞了你的笔记")
                .build();
    }

    @Test
    @DisplayName("新建通知：未读 +1；离线不推送")
    void newNotificationIncrementsUnread() {
        Notification created = new Notification().setUserId(9L).setType(1);
        created.setId(500L);
        when(aggregator.processWithAggregate(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(500L);
            return n;
        });
        when(sseEmitterManager.isOnlineAnywhere(9L)).thenReturn(false);

        service.processEvent(event());

        verify(unreadCountService).incrementUnread(9L, 1);
        verify(sseEmitterManager, never()).pushNotification(anyLong(), any());
    }

    @Test
    @DisplayName("聚合更新：不增加未读计数（返回主通知 id ≠ 当前对象 id）")
    void aggregateUpdateDoesNotIncrementUnread() {
        Notification incoming = new Notification().setUserId(9L).setType(1);
        Notification main = new Notification().setUserId(9L).setType(1);
        main.setId(500L);
        when(aggregator.processWithAggregate(any())).thenReturn(main);
        when(sseEmitterManager.isOnlineAnywhere(9L)).thenReturn(false);

        service.processEvent(event());

        verify(unreadCountService, never()).incrementUnread(anyLong(), anyInt());
        verify(unreadCountService, never()).incrementUnread(eq(9L), anyInt());
    }

    @Test
    @DisplayName("C-012 F3：未读增量失败不抛出（降级，对账兜底）")
    void unreadIncrementFailureSwallowed() {
        when(aggregator.processWithAggregate(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(500L);
            return n;
        });
        doThrow(new RuntimeException("Redis down")).when(unreadCountService).incrementUnread(9L, 1);
        when(sseEmitterManager.isOnlineAnywhere(9L)).thenReturn(false);

        service.processEvent(event()); // 不抛

        verify(unreadCountService).incrementUnread(9L, 1);
    }

    @Test
    @DisplayName("在线用户：推送通知 + 推送未读计数")
    void onlineUserGetsPush() {
        when(aggregator.processWithAggregate(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(500L);
            return n;
        });
        when(sseEmitterManager.isOnlineAnywhere(9L)).thenReturn(true);
        when(unreadCountService.getUnreadCount(9L))
                .thenReturn(UnreadCountVO.builder().total(1).details(Map.of(1, 1)).build());

        service.processEvent(event());

        verify(sseEmitterManager).pushNotification(eq(9L), any(NotificationVO.class));
        verify(sseEmitterManager).pushUnreadCount(eq(9L), any(UnreadCountVO.class));
    }

    @Test
    @DisplayName("C-012 F3：读未读计数失败不抛出（跳过计数推送）")
    void unreadCountReadFailureSwallowed() {
        when(aggregator.processWithAggregate(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(500L);
            return n;
        });
        when(sseEmitterManager.isOnlineAnywhere(9L)).thenReturn(true);
        doThrow(new RuntimeException("Redis down")).when(unreadCountService).getUnreadCount(9L);

        service.processEvent(event()); // 不抛

        verify(sseEmitterManager).pushNotification(eq(9L), any(NotificationVO.class));
        verify(sseEmitterManager, never()).pushUnreadCount(anyLong(), any());
    }

    @Test
    @DisplayName("标记单条已读：不存在/非本人 → BizException")
    void markAsReadGuard() {
        when(notificationMapper.selectById(500L)).thenReturn(null);

        assertThatThrownBy(() -> service.markAsRead(9L, 500L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("通知不存在");
    }

    @Test
    @DisplayName("标记单条已读：已读幂等（不重复 DECR）")
    void markAsReadIdempotent() {
        Notification read = new Notification().setUserId(9L).setType(1).setIsRead(1);
        read.setId(500L);
        when(notificationMapper.selectById(500L)).thenReturn(read);

        service.markAsRead(9L, 500L);

        verify(notificationMapper, never()).markAsRead(anyLong(), anyLong());
        verify(unreadCountService, never()).decrementUnread(anyLong(), anyInt());
    }

    @Test
    @DisplayName("标记单条已读：成功 → DECR")
    void markAsReadDecrements() {
        Notification unread = new Notification().setUserId(9L).setType(1).setIsRead(0);
        unread.setId(500L);
        when(notificationMapper.selectById(500L)).thenReturn(unread);
        when(notificationMapper.markAsRead(9L, 500L)).thenReturn(1);

        service.markAsRead(9L, 500L);

        verify(unreadCountService).decrementUnread(9L, 1);
    }

    @Test
    @DisplayName("全部已读 / 按类型已读：affected>0 才重置")
    void markAllReadResetsOnlyWhenAffected() {
        when(notificationMapper.markAllAsRead(9L)).thenReturn(3);
        service.markAllAsRead(9L);
        verify(unreadCountService).resetUnread(9L);

        when(notificationMapper.markAllReadByType(9L, 2)).thenReturn(0);
        service.markAllReadByType(9L, 2);
        verify(unreadCountService, never()).resetUnreadByType(9L, 2);
    }
}
