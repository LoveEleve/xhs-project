package com.myxhs.order.service;

import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.SubOrder;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.mapper.SubOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubOrderService 单元测试（ITER-001 拆单）
 * <p>
 * 覆盖：父单状态聚合（表驱动穷尽，AC-4）+ 聚合重算（事件收敛/冲突重试/幂等）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SubOrderServiceTest {

    @Mock
    private SubOrderMapper subOrderMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderEventService orderEventService;

    private SubOrderService subOrderService;

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 123456L;

    @BeforeEach
    void setUp() {
        subOrderService = new SubOrderService(subOrderMapper, orderMapper, orderEventService);
    }

    // ==================== 聚合函数（表驱动穷尽，AC-4） ====================

    static Stream<Arguments> aggregationCases() {
        return Stream.of(
                // 单子单
                Arguments.of(List.of(0), 0),
                Arguments.of(List.of(1), 1),
                Arguments.of(List.of(2), 2),
                Arguments.of(List.of(3), 3),
                Arguments.of(List.of(4), 4),
                Arguments.of(List.of(5), 5),
                // 全待付款 / 全待发货 / 全已发货
                Arguments.of(List.of(0, 0), 0),
                Arguments.of(List.of(1, 1), 1),
                Arguments.of(List.of(1, 1, 1), 1),
                Arguments.of(List.of(2, 2), 2),
                // 部分发货：存在未发货活跃子单 → 1
                Arguments.of(List.of(1, 2), 1),
                Arguments.of(List.of(2, 2, 1), 1),
                // 全终态：全取消/全退款/混合终态
                Arguments.of(List.of(4, 4), 4),
                Arguments.of(List.of(5, 5), 5),
                Arguments.of(List.of(3, 3), 3),
                Arguments.of(List.of(3, 4), 3),
                Arguments.of(List.of(3, 5), 3),
                Arguments.of(List.of(4, 5), 3),
                Arguments.of(List.of(3, 4, 5), 3),
                // 混合：终态子单不拉低父单（活跃子单决定进度）
                Arguments.of(List.of(2, 3), 2),
                Arguments.of(List.of(1, 3), 1),
                Arguments.of(List.of(1, 4), 1),
                Arguments.of(List.of(2, 4), 2),
                Arguments.of(List.of(2, 5), 2),
                Arguments.of(List.of(0, 4), 0)
        );
    }

    @ParameterizedTest(name = "子单状态 {0} → 父单 {1}")
    @MethodSource("aggregationCases")
    @DisplayName("computeParentStatus - 聚合规则穷尽（表驱动）")
    void computeParentStatus_exhaustive(List<Integer> subStatuses, int expected) {
        List<SubOrder> subs = subStatuses.stream().map(status -> {
            SubOrder s = new SubOrder();
            s.setStatus(status);
            return s;
        }).toList();
        assertThat(SubOrderService.computeParentStatus(subs)).isEqualTo(expected);
    }

    @Test
    @DisplayName("computeParentStatus - 空列表：不变量破坏（抛异常）")
    void computeParentStatus_emptyList() {
        assertThatThrownBy(() -> SubOrderService.computeParentStatus(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 聚合重算 ====================

    @Test
    @DisplayName("recomputeParentStatus - 已收敛：不追加事件、返回 false")
    void recompute_alreadyConverged() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of(buildSub(2), buildSub(2)));
        Order order = buildOrder(2);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isFalse();
        verify(orderEventService, never()).appendEvent(any(), anyString(), any());
    }

    @Test
    @DisplayName("recomputeParentStatus - 全子单发货：父单 1→2（聚合事件）")
    void recompute_allShipped() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of(buildSub(2), buildSub(2)));
        Order order = buildOrder(1);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isTrue();
        verify(orderEventService).appendEvent(eq(order),
                eq(OrderEventService.EVENT_ALL_SUBS_SHIPPED), any());
    }

    @Test
    @DisplayName("recomputeParentStatus - 全部终态且成功：父单 →3（聚合事件）")
    void recompute_allCompleted() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of(buildSub(3), buildSub(3)));
        Order order = buildOrder(2);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isTrue();
        verify(orderEventService).appendEvent(eq(order),
                eq(OrderEventService.EVENT_ALL_SUBS_COMPLETED), any());
    }

    @Test
    @DisplayName("recomputeParentStatus - 混合终态（部分取消 + 部分完成）：父单 1→3")
    void recompute_mixedTerminal() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of(buildSub(3), buildSub(4)));
        Order order = buildOrder(1);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isTrue();
        verify(orderEventService).appendEvent(eq(order),
                eq(OrderEventService.EVENT_ALL_SUBS_COMPLETED), any());
    }

    @Test
    @DisplayName("recomputeParentStatus - CAS 冲突：重试后成功（最终收敛）")
    void recompute_conflictRetry() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of(buildSub(2), buildSub(2)));
        Order order = buildOrder(1);
        when(orderMapper.selectOne(any())).thenReturn(order);
        doThrow(new IllegalStateException("订单状态并发冲突"))
                .doNothing()
                .when(orderEventService).appendEvent(any(), anyString(), any());

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isTrue();
        verify(orderEventService, times(2)).appendEvent(eq(order),
                eq(OrderEventService.EVENT_ALL_SUBS_SHIPPED), any());
    }

    @Test
    @DisplayName("recomputeParentStatus - 子单缺失：不变量破坏，安全返回 false")
    void recompute_missingSubs() {
        when(subOrderMapper.selectByOrderId(ORDER_ID, USER_ID)).thenReturn(List.of());

        assertThat(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).isFalse();
        verify(orderEventService, never()).appendEvent(any(), anyString(), any());
    }

    // ==================== 辅助 ====================

    private SubOrder buildSub(int status) {
        SubOrder sub = new SubOrder();
        sub.setId((long) status + 9000);
        sub.setOrderId(ORDER_ID);
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        return sub;
    }

    private Order buildOrder(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setStatus(status);
        return order;
    }
}
