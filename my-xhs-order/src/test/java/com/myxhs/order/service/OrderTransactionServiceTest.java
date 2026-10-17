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
    private com.myxhs.order.mapper.SubOrderMapper subOrderMapper;
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
                orderMapper, orderItemMapper, subOrderMapper, localMessageMapper,
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

    @Test
    @DisplayName("executeLocalTransaction - 分摊落库：优惠按金额比例摊到行（Σ=优惠额）")
    void executeLocalTransaction_allocationPersisted() {
        // 2 SKU：200 / 100（总 300），优惠 30 → 按金额比例分摊 20 / 10（最大余数法）
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(java.util.Arrays.asList(
                createSkuItem(10001L, 2),
                createSkuItem(10002L, 1)
        ));
        request.setCouponId(5001L);
        request.setAddressId(1L);
        request.setBizIdentifier("biz-tx-005");

        java.util.Map<Long, com.myxhs.order.dto.SkuInfoDTO> skuMap = java.util.Map.of(
                10001L, buildSku(10001L, "100.00"),
                10002L, buildSku(10002L, "100.00"));

        String orderNo = "ORD20250101000000005";
        String payload = "{\"orderNo\":\"ORD20250101000000005\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1005L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, new BigDecimal("300.00"),
                new BigDecimal("30.00"), new BigDecimal("270.00"), payload, "{}", skuMap);

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(2)).insert(captor.capture());
        java.util.List<OrderItem> items = captor.getAllValues();

        // 按金额比例：200 摊 20、100 摊 10；Σ分摊 = 优惠额（守恒）
        assertThat(items).extracting(OrderItem::getDiscountShare)
                .containsExactlyInAnyOrder(new BigDecimal("20.00"), new BigDecimal("10.00"));
        BigDecimal sum = items.stream().map(OrderItem::getDiscountShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("executeLocalTransaction - 分摊取整：最大余数法（平局按行 ID 升序补分）")
    void executeLocalTransaction_allocationRounding() {
        // 3 SKU 各 100（总 300），优惠 10 → 各 3.33 分，余 1 分 → 平局按最小行 ID 补为 3.34
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(java.util.Arrays.asList(
                createSkuItem(10001L, 1),
                createSkuItem(10002L, 1),
                createSkuItem(10003L, 1)
        ));
        request.setCouponId(null);
        request.setAddressId(1L);
        request.setBizIdentifier("biz-tx-006");

        java.util.Map<Long, com.myxhs.order.dto.SkuInfoDTO> skuMap = java.util.Map.of(
                10001L, buildSku(10001L, "100.00"),
                10002L, buildSku(10002L, "100.00"),
                10003L, buildSku(10003L, "100.00"));

        String orderNo = "ORD20250101000000006";
        String payload = "{\"orderNo\":\"ORD20250101000000006\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1006L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, new BigDecimal("300.00"),
                new BigDecimal("10.00"), new BigDecimal("290.00"), payload, "{}", skuMap);

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(3)).insert(captor.capture());
        java.util.List<OrderItem> items = new java.util.ArrayList<>(captor.getAllValues());

        BigDecimal sum = items.stream().map(OrderItem::getDiscountShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10.00"); // 守恒：取整差额不丢
        items.sort(java.util.Comparator.comparing(OrderItem::getId));
        assertThat(items.get(0).getDiscountShare()).isEqualByComparingTo("3.34"); // 余分给最小 ID
        assertThat(items.get(1).getDiscountShare()).isEqualByComparingTo("3.33");
        assertThat(items.get(2).getDiscountShare()).isEqualByComparingTo("3.33");
    }

    @Test
    @DisplayName("executeLocalTransaction - 无优惠：全行分摊为 0")
    void executeLocalTransaction_noDiscount() {
        OrderCreateRequest request = buildCreateRequest("biz-tx-007");
        String orderNo = "ORD20250101000000007";
        String payload = "{\"orderNo\":\"ORD20250101000000007\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1007L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, new BigDecimal("99.00"),
                BigDecimal.ZERO, new BigDecimal("99.00"), payload, "{}",
                java.util.Map.of(10001L, buildSku(10001L, "99.00")));

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getDiscountShare()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("executeLocalTransaction - 拆单：按卖家分组生成子单（金额守恒、行挂子单）")
    void executeLocalTransaction_splitBySeller() {
        // 2 卖家：10001/10002（卖家 2001，300 元）；10003（卖家 2002，100 元）；总优惠 40
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(java.util.Arrays.asList(
                createSkuItem(10001L, 2),
                createSkuItem(10002L, 1),
                createSkuItem(10003L, 1)
        ));
        request.setCouponId(5001L);
        request.setAddressId(1L);
        request.setBizIdentifier("biz-tx-008");

        java.util.Map<Long, com.myxhs.order.dto.SkuInfoDTO> skuMap = java.util.Map.of(
                10001L, buildSku(10001L, "100.00", 2001L),
                10002L, buildSku(10002L, "100.00", 2001L),
                10003L, buildSku(10003L, "100.00", 2002L));

        String orderNo = "ORD20250101000000008";
        String payload = "{\"orderNo\":\"ORD20250101000000008\"}";

        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1008L);
            return 1;
        }).when(orderMapper).insert((Order) any());
        doNothing().when(orderEventService).appendEvent(any(Order.class), anyString(), any());
        when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);
        when(subOrderMapper.insert((com.myxhs.order.entity.SubOrder) any())).thenReturn(1);
        when(localMessageMapper.insert((LocalMessage) any())).thenReturn(1);

        transactionService.executeLocalTransaction(
                USER_ID, request, orderNo, new BigDecimal("400.00"),
                new BigDecimal("40.00"), new BigDecimal("360.00"), payload, "{}", skuMap);

        // 子单：2 个（按卖家分组），金额守恒 Σ子单实付 = 父单实付
        ArgumentCaptor<com.myxhs.order.entity.SubOrder> subCaptor =
                ArgumentCaptor.forClass(com.myxhs.order.entity.SubOrder.class);
        verify(subOrderMapper, times(2)).insert(subCaptor.capture());
        java.util.List<com.myxhs.order.entity.SubOrder> subs = subCaptor.getAllValues();
        assertThat(subs).extracting(com.myxhs.order.entity.SubOrder::getSellerId)
                .containsExactlyInAnyOrder(2001L, 2002L);
        BigDecimal subPaySum = subs.stream().map(com.myxhs.order.entity.SubOrder::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(subPaySum).isEqualByComparingTo("360.00");
        assertThat(subs).filteredOn(s -> s.getSellerId() == 2001L)
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getTotalAmount()).isEqualByComparingTo("300.00");
                    assertThat(s.getDiscountAmount()).isEqualByComparingTo("30.00");
                    assertThat(s.getPayAmount()).isEqualByComparingTo("270.00");
                });

        // 行挂子单：3 行都绑定到子单，且卖家 2001 的行指向 2001 的子单
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(3)).insert(itemCaptor.capture());
        java.util.List<OrderItem> items = itemCaptor.getAllValues();
        assertThat(items).allSatisfy(i -> assertThat(i.getSubOrderId()).isNotNull());
        Long sub2001Id = subs.stream().filter(s -> s.getSellerId() == 2001L).findFirst().orElseThrow().getId();
        assertThat(items).filteredOn(i -> i.getSellerId() == 2001L)
                .allSatisfy(i -> assertThat(i.getSubOrderId()).isEqualTo(sub2001Id));
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

    private com.myxhs.order.dto.SkuInfoDTO buildSku(Long skuId, String price) {
        return buildSku(skuId, price, null);
    }

    private com.myxhs.order.dto.SkuInfoDTO buildSku(Long skuId, String price, Long sellerId) {
        com.myxhs.order.dto.SkuInfoDTO sku = new com.myxhs.order.dto.SkuInfoDTO();
        sku.setId(skuId);
        sku.setSpuId(skuId);
        sku.setName("SKU-" + skuId);
        sku.setPrice(new BigDecimal(price));
        sku.setSellerId(sellerId);
        return sku;
    }
}
