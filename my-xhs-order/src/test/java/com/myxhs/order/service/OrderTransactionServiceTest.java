package com.myxhs.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderItem;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.mapper.OrderItemMapper;
import com.myxhs.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderTransactionService 单元测试
 * <p>
 * 测试本地事务方法 executeLocalTransaction 的正常和异常场景。
 * 注意：由于 @Transactional 是 AOP 代理，单元测试中不会触发事务行为，
 * 这里只验证方法内的业务逻辑（Mapper 调用、数据组装等）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTransactionServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private LocalMessageMapper localMessageMapper;
    @Mock
    private OrderEventService orderEventService;

    private ObjectMapper objectMapper;
    private OrderTransactionService transactionService;

    private static final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transactionService = new OrderTransactionService(
                orderMapper, orderItemMapper, localMessageMapper,
                orderEventService, objectMapper
        );
    }

    @Test
    @DisplayName("executeLocalTransaction - 正常创建订单、明细和本地消息表")
    void executeLocalTransaction_success() {
        OrderCreateRequest request = buildCreateRequest("biz-tx-001");
        String orderNo = "ORD20250101000000001";
        BigDecimal totalAmount = new BigDecimal("99.00");
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal payAmount = new BigDecimal("99.00");
        String payload = "{\"orderNo\":\"ORD20250101000000001\"}";

        // orderMapper.insert 成功后，Order 对象上会设置 ID
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1001L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        Order order = transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, totalAmount, discountAmount, payAmount,
                payload, "{}", Collections.emptyMap());

        assertThat(order).isNotNull();
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        assertThat(order.getOrderNo()).isEqualTo(orderNo);
        assertThat(order.getStatus()).isEqualTo(0); // 待付款
        assertThat(order.getPayAmount()).isEqualByComparingTo("99.00");

        // 验证 OrderMapper.insert 被调用
        verify(orderMapper).insert((Order) any());
        // 验证 OrderItemMapper.insert 被调用（1个 SKU）
        verify(orderItemMapper, times(1)).insert((OrderItem) any());
        // 验证 LocalMessageMapper.insert 被调用
        verify(localMessageMapper).insert((LocalMessage) any());
    }

    @Test
    @DisplayName("executeLocalTransaction - OrderMapper.insert 失败时异常传播")
    void executeLocalTransaction_insertOrderFails() {
        OrderCreateRequest request = buildCreateRequest("biz-tx-002");
        String orderNo = "ORD20250101000000002";
        BigDecimal totalAmount = new BigDecimal("99.00");
        String payload = "{\"orderNo\":\"ORD20250101000000002\"}";

        when(orderMapper.insert((Order) any()))
                .thenThrow(new RuntimeException("数据库插入失败"));

        assertThatThrownBy(() -> transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, totalAmount,
                BigDecimal.ZERO, totalAmount, payload, "{}", Collections.emptyMap()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("数据库插入失败");

        // 订单插入失败后，不应继续插入订单明细
        verify(orderItemMapper, never()).insert((OrderItem) any());
        verify(localMessageMapper, never()).insert((LocalMessage) any());
    }

    @Test
    @DisplayName("executeLocalTransaction - 多 SKU 订单")
    void executeLocalTransaction_multipleSkus() {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(java.util.Arrays.asList(
                createSkuItem(10001L, 2),
                createSkuItem(10002L, 1)
        ));
        request.setCouponId(null);
        request.setAddressId(1L);
        request.setBizIdentifier("biz-tx-003");

        String orderNo = "ORD20250101000000003";
        BigDecimal totalAmount = new BigDecimal("297.00");
        String payload = "{\"orderNo\":\"ORD20250101000000003\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1003L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        Order order = transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, totalAmount,
                BigDecimal.ZERO, totalAmount, payload, "{}", Collections.emptyMap());

        assertThat(order).isNotNull();
        // 验证 2 个 SKU 明细都被插入
        verify(orderItemMapper, times(2)).insert((OrderItem) any());
    }

    @Test
    @DisplayName("executeLocalTransaction - 带优惠券的订单")
    void executeLocalTransaction_withCoupon() {
        OrderCreateRequest request = buildCreateRequest("biz-tx-004");
        request.setCouponId(5001L);

        String orderNo = "ORD20250101000000004";
        BigDecimal totalAmount = new BigDecimal("99.00");
        BigDecimal discountAmount = new BigDecimal("10.00");
        BigDecimal payAmount = new BigDecimal("89.00");
        String payload = "{\"orderNo\":\"ORD20250101000000004\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1004L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        Order order = transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, totalAmount, discountAmount, payAmount,
                payload, "{}", Collections.emptyMap());

        assertThat(order).isNotNull();
        assertThat(order.getCouponId()).isEqualTo(5001L);
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(order.getPayAmount()).isEqualByComparingTo("89.00");
    }

    // ==================== 辅助方法 ====================

    private OrderCreateRequest buildCreateRequest(String bizIdentifier) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(Collections.singletonList(createSkuItem(10001L, 1)));
        request.setCouponId(null);
        request.setAddressId(1L);
        request.setBizIdentifier(bizIdentifier);
        return request;
    }

    private OrderCreateRequest.SkuItem createSkuItem(Long skuId, int quantity) {
        OrderCreateRequest.SkuItem item = new OrderCreateRequest.SkuItem();
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }
}
