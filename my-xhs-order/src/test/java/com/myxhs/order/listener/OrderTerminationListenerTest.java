package com.myxhs.order.listener;

import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.enums.TerminationReason;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.service.OrderTerminationService;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderTerminationListener 单元测试（04B 终结事务消息监听器）
 * <p>
 * 重点覆盖：本地事务结果 → COMMIT/ROLLBACK 映射；回查必须按 operation_type 过滤
 * （防误命中同订单的 ORDER_CREATED 行——Review C2）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTerminationListenerTest {

    @Mock
    private OrderTerminationService terminationService;
    @Mock
    private LocalMessageMapper localMessageMapper;

    private OrderTerminationListener listener;

    private static final Long ORDER_ID = 123456L;
    private static final Long USER_ID = 1001L;
    private static final String ORDER_NO = "ORD20250101000000001";

    @BeforeEach
    void setUp() {
        listener = new OrderTerminationListener(terminationService, localMessageMapper);
    }

    private OrderTerminationListener.OrderTerminationContext buildContext() {
        return OrderTerminationListener.OrderTerminationContext.builder()
                .orderId(ORDER_ID).userId(USER_ID).orderNo(ORDER_NO)
                .reason(TerminationReason.USER_CANCELLED).source("APP")
                .eventPayload("{\"orderNo\":\"" + ORDER_NO + "\"}")
                .build();
    }

    @Test
    @DisplayName("本地事务 - 终结成功 → COMMIT 且回写 outcome")
    void executeLocalTransaction_terminated_commit() {
        when(terminationService.executeLocalTransaction(eq(ORDER_ID), eq(USER_ID),
                eq(TerminationReason.USER_CANCELLED), eq("APP"), any()))
                .thenReturn(OrderTerminationService.Outcome.TERMINATED);

        OrderTerminationListener.OrderTerminationContext context = buildContext();
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                MessageBuilder.withPayload("x").build(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(context.getOutcome()).isEqualTo(OrderTerminationService.Outcome.TERMINATED);
    }

    @Test
    @DisplayName("本地事务 - 状态不允许 → ROLLBACK 且回写 outcome")
    void executeLocalTransaction_invalidStatus_rollback() {
        when(terminationService.executeLocalTransaction(eq(ORDER_ID), eq(USER_ID), any(), any(), any()))
                .thenReturn(OrderTerminationService.Outcome.INVALID_STATUS);

        OrderTerminationListener.OrderTerminationContext context = buildContext();
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                MessageBuilder.withPayload("x").build(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        assertThat(context.getOutcome()).isEqualTo(OrderTerminationService.Outcome.INVALID_STATUS);
    }

    @Test
    @DisplayName("本地事务 - 抛异常（并发竞态）→ ROLLBACK 且 outcome 保持 null")
    void executeLocalTransaction_exception_rollback() {
        when(terminationService.executeLocalTransaction(eq(ORDER_ID), eq(USER_ID), any(), any(), any()))
                .thenThrow(new IllegalStateException("乐观锁冲突"));

        OrderTerminationListener.OrderTerminationContext context = buildContext();
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                MessageBuilder.withPayload("x").build(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        assertThat(context.getOutcome()).isNull();
    }

    @Test
    @DisplayName("回查 - 有终结本地消息 → COMMIT（按 operation_type 过滤，防误命中创建行）")
    void checkLocalTransaction_committed_commit() {
        when(localMessageMapper.selectByTransactionIdAndUserIdAndOperationType(
                eq(ORDER_NO), eq(USER_ID), eq(OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED)))
                .thenReturn(new LocalMessage());

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                MessageBuilder.withPayload("x")
                        .setHeader("orderNo", ORDER_NO)
                        .setHeader("userId", USER_ID.toString())
                        .build());

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        verify(localMessageMapper).selectByTransactionIdAndUserIdAndOperationType(
                eq(ORDER_NO), eq(USER_ID), eq(OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED));
    }

    @Test
    @DisplayName("回查 - 无终结本地消息（事务回滚）→ ROLLBACK")
    void checkLocalTransaction_rolledback_rollback() {
        when(localMessageMapper.selectByTransactionIdAndUserIdAndOperationType(any(), any(), any()))
                .thenReturn(null);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                MessageBuilder.withPayload("x")
                        .setHeader("orderNo", ORDER_NO)
                        .setHeader("userId", USER_ID.toString())
                        .build());

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    @DisplayName("回查 - 缺少 orderNo header → ROLLBACK")
    void checkLocalTransaction_missingHeader_rollback() {
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                MessageBuilder.withPayload("x").build());

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }
}
