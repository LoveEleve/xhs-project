package com.myxhs.notification.service;

import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.mapper.PushTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
 * 通知聚合器单测（C-012）
 * <p>
 * 验证口径：窗口选举（新建/聚合/订单刷新）+ F4 自愈四路径（INSERT 失败/回填失败/PENDING 超时/撞空行/脏值）
 * ——自愈路径均删键（后续事件重选窗口）。
 * </p>
 */
@SuppressWarnings("unchecked")
class NotificationAggregatorTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private NotificationMapper notificationMapper;
    private PushTemplateMapper pushTemplateMapper;
    private NotificationAggregator aggregator;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        notificationMapper = mock(NotificationMapper.class);
        pushTemplateMapper = mock(PushTemplateMapper.class);
        aggregator = new NotificationAggregator(redisTemplate, notificationMapper, pushTemplateMapper);
    }

    private static Notification incoming(int type) {
        return new Notification().setUserId(9L).setType(type).setTargetId(100L).setSenderName("小马");
    }

    private void stubScript(String result) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class),
                anyString(), anyString())).thenReturn(result);
    }

    @Test
    @DisplayName("窗口首条：INSERT + 回填真实 ID + count=1")
    void newWindow() {
        stubScript("1");
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(500L);
            return 1;
        });

        Notification result = aggregator.processWithAggregate(incoming(1));

        assertThat(result.getAggregateCount()).isEqualTo(1);
        assertThat(result.getIsRead()).isEqualTo(0);
        verify(valueOps).set(anyString(), eq("500"), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("窗口后续：increment + 标题重建 + 返回主通知")
    void aggregateUpdate() {
        stubScript("500");
        when(notificationMapper.incrementAggregateCount(500L)).thenReturn(1);
        when(notificationMapper.getAggregateCount(500L)).thenReturn(2);
        Notification main = incoming(1);
        main.setId(500L);
        main.setAggregateCount(2);
        when(notificationMapper.selectById(500L)).thenReturn(main);

        Notification result = aggregator.processWithAggregate(incoming(1));

        assertThat(result.getId()).isEqualTo(500L);
        verify(notificationMapper).incrementAggregateCount(500L);
        verify(notificationMapper).updateAggregateTitle(eq(500L), anyString());
    }

    @Test
    @DisplayName("订单通知（type=5）：刷新最新状态（updateOrderLatest），不做社交化标题")
    void orderRefresh() {
        stubScript("500");
        when(notificationMapper.incrementAggregateCount(500L)).thenReturn(1);
        when(notificationMapper.getAggregateCount(500L)).thenReturn(3);
        Notification main = incoming(5);
        main.setId(500L);
        when(notificationMapper.selectById(500L)).thenReturn(main);
        Notification incomingOrder = incoming(5);
        incomingOrder.setContent("订单 ORDER-1 已发货");

        aggregator.processWithAggregate(incomingOrder);

        verify(notificationMapper).updateOrderLatest(eq(500L), anyString(), any());
        verify(notificationMapper, never()).updateAggregateTitle(anyLong(), anyString());
    }

    @Test
    @DisplayName("F4②：PENDING 超时 → 删键自愈 + 独立插入")
    void pendingTimeoutSelfHeals() {
        stubScript("PENDING");
        when(valueOps.get(anyString())).thenReturn("PENDING");

        Notification result = aggregator.processWithAggregate(incoming(1));

        verify(redisTemplate).delete(anyString());
        verify(notificationMapper).insert(any(Notification.class));
        assertThat(result.getAggregateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("F4①：INSERT 失败 → 删键自愈 + 异常上抛（事务回滚 → MQ 重试重选窗口）")
    void insertFailureSelfHeals() {
        stubScript("1");
        when(notificationMapper.insert(any(Notification.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> aggregator.processWithAggregate(incoming(1)))
                .isInstanceOf(RuntimeException.class);
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("F4①（同族）：回填真实 ID 失败 → 删键自愈 + 异常上抛")
    void backfillFailureSelfHeals() {
        stubScript("1");
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(500L);
            return 1;
        });
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        assertThatThrownBy(() -> aggregator.processWithAggregate(incoming(1)))
                .isInstanceOf(RuntimeException.class);
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("F4③：increment 撞空行（affected=0）→ 删键自愈 + 独立插入")
    void affectedZeroSelfHeals() {
        stubScript("500");
        when(notificationMapper.incrementAggregateCount(500L)).thenReturn(0);

        Notification result = aggregator.processWithAggregate(incoming(1));

        verify(redisTemplate).delete(anyString());
        verify(notificationMapper).insert(any(Notification.class));
        assertThat(result.getAggregateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("F4（同族）：Redis 脏值 → 删键自愈 + 独立插入")
    void dirtyValueSelfHeals() {
        stubScript("not-a-number");

        Notification result = aggregator.processWithAggregate(incoming(1));

        verify(redisTemplate).delete(anyString());
        verify(notificationMapper).insert(any(Notification.class));
        assertThat(result.getAggregateCount()).isEqualTo(1);
    }
}
