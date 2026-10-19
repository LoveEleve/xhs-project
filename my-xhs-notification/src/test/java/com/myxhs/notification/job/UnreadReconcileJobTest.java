package com.myxhs.notification.job;

import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.service.UnreadCountService;
import com.myxhs.notification.dto.UnreadCountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未读对账 Job 单测（C-012）
 * <p>
 * 验证口径：id 游标批扫（跨批累计不覆盖）、DB 为准修复、Redis 残留清零、180 天日清（SETNX 日切）。
 * </p>
 */
@SuppressWarnings("unchecked")
class UnreadReconcileJobTest {

    private NotificationMapper notificationMapper;
    private UnreadCountService unreadCountService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private UnreadReconcileJob job;

    @org.junit.jupiter.api.BeforeAll
    static void initMpLambdaCache() {
        // 纯 Mockito 测试无 Spring 上下文需手动初始化（否则 can not find lambda cache）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                Notification.class);
    }

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        unreadCountService = mock(UnreadCountService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        job = new UnreadReconcileJob(notificationMapper, unreadCountService, redisTemplate);
    }

    private static Notification unread(long id, long userId, int type) {
        Notification n = new Notification().setUserId(userId).setType(type).setIsRead(0);
        n.setId(id);
        return n;
    }

    private void stubNoStaleKeys() {
        Cursor<String> empty = mock(Cursor.class);
        when(empty.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(empty);
    }

    private void stubPurgeSkipped() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
    }

    @Test
    @DisplayName("对账修复：Redis 计数与 DB 不符 → forceSetUnread（DB 为准）")
    void reconcileFixesMismatch() throws Exception {
        stubNoStaleKeys();
        stubPurgeSkipped();
        when(notificationMapper.selectList(any()))
                .thenReturn(List.of(unread(1, 9, 1), unread(2, 9, 1), unread(3, 9, 2)));
        when(unreadCountService.getUnreadCount(9L))
                .thenReturn(UnreadCountVO.builder().total(0).details(Map.of()).build());

        int[] result = ReflectionTestUtils.invokeMethod(job, "doReconcile");

        assertThat(result[0]).isEqualTo(1); // 检查 1 个用户
        assertThat(result[1]).isEqualTo(1); // 修复 1 个
        ArgumentCaptor<Map<Integer, Integer>> captor = ArgumentCaptor.forClass(Map.class);
        verify(unreadCountService).forceSetUnread(eq(9L), eq(3), captor.capture());
        assertThat(captor.getValue()).containsEntry(1, 2).containsEntry(2, 1);
    }

    @Test
    @DisplayName("Redis 残留清零：DB 无未读 → 清零（forceSetUnread 0）")
    void staleKeyCleared() throws Exception {
        stubPurgeSkipped();
        when(notificationMapper.selectList(any())).thenReturn(List.of());
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("myxhs:notification:unread:{u:77}");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        int[] result = ReflectionTestUtils.invokeMethod(job, "doReconcile");

        assertThat(result[1]).isEqualTo(1);
        verify(unreadCountService).forceSetUnread(eq(77L), eq(0), any());
    }

    @Test
    @DisplayName("180 天日清：SETNX 抢到标记才清（有界排空）")
    void dailyPurgeBounded() throws Exception {
        stubNoStaleKeys();
        when(notificationMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(notificationMapper.deleteStale(any(LocalDateTime.class), anyInt())).thenReturn(10);

        ReflectionTestUtils.invokeMethod(job, "doReconcile");

        verify(notificationMapper, times(1)).deleteStale(any(LocalDateTime.class), eq(5000));
    }

    @Test
    @DisplayName("日清未抢到标记（已跑过）→ 不清理")
    void dailyPurgeSkippedWhenMarkerExists() throws Exception {
        stubNoStaleKeys();
        stubPurgeSkipped();
        when(notificationMapper.selectList(any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(job, "doReconcile");

        verify(notificationMapper, never()).deleteStale(any(LocalDateTime.class), anyInt());
    }
}
