package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.enums.TerminationReason;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderTerminationService 单元测试（04B 终结事务内核）
 * <p>
 * 覆盖：状态校验四态（TERMINATED / ALREADY_TERMINAL / INVALID_STATUS / NOT_FOUND）、
 * 0→4 迁移 + 终结事件写本地消息表（与状态迁移同事务）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTerminationServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private LocalMessageMapper localMessageMapper;
    @Mock
    private OrderEventService orderEventService;

    private OrderTerminationService terminationService;

    private static final Long ORDER_ID = 123456L;
    private static final Long USER_ID = 1001L;
    private static final String ORDER_NO = "ORD20250101000000001";
    private static final String PAYLOAD = "{\"orderNo\":\"" + ORDER_NO + "\"}";

    @BeforeEach
    void setUp() {
        terminationService = new OrderTerminationService(orderMapper, localMessageMapper, orderEventService);
    }

    private Order buildOrder(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setOrderNo(ORDER_NO);
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("终结 - 待付款订单：0→4 迁移 + 终结事件落本地消息表")
    void execute_terminated() {
        Order order = buildOrder(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        OrderTerminationService.Outcome outcome = terminationService.executeLocalTransaction(
                ORDER_ID, USER_ID, TerminationReason.USER_CANCELLED, "APP", PAYLOAD);

        assertThat(outcome).isEqualTo(OrderTerminationService.Outcome.TERMINATED);
        verify(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_CANCELLED), anyMap());
        verify(orderMapper).setCancelledAt(ORDER_ID, USER_ID);
        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        LocalMessage message = captor.getValue();
        assertThat(message.getOperationType()).isEqualTo(OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED);
        assertThat(message.getTransactionId()).isEqualTo(ORDER_NO);
        assertThat(message.getPayload()).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(0);
        assertThat(message.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("终结 - 已是终态（4/5）：幂等返回，不写任何数据")
    void execute_alreadyTerminal() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildOrder(4));

        OrderTerminationService.Outcome outcome = terminationService.executeLocalTransaction(
                ORDER_ID, USER_ID, TerminationReason.USER_CANCELLED, "APP", PAYLOAD);

        assertThat(outcome).isEqualTo(OrderTerminationService.Outcome.ALREADY_TERMINAL);
        verify(orderEventService, never()).appendEvent(any(), any(), any());
        verify(localMessageMapper, never()).insert(any(LocalMessage.class));
    }

    @Test
    @DisplayName("终结 - 已付款：状态不允许")
    void execute_invalidStatus() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildOrder(1));

        OrderTerminationService.Outcome outcome = terminationService.executeLocalTransaction(
                ORDER_ID, USER_ID, TerminationReason.TIMEOUT, "TIMEOUT_JOB", PAYLOAD);

        assertThat(outcome).isEqualTo(OrderTerminationService.Outcome.INVALID_STATUS);
        verify(localMessageMapper, never()).insert(any(LocalMessage.class));
    }

    @Test
    @DisplayName("终结 - 订单不存在")
    void execute_notFound() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        OrderTerminationService.Outcome outcome = terminationService.executeLocalTransaction(
                ORDER_ID, USER_ID, TerminationReason.USER_CANCELLED, "APP", PAYLOAD);

        assertThat(outcome).isEqualTo(OrderTerminationService.Outcome.NOT_FOUND);
        verify(localMessageMapper, never()).insert(any(LocalMessage.class));
    }
}
