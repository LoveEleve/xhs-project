package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.order.dto.SkuInfoDTO;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.dto.request.PayRequest;
import com.myxhs.order.dto.response.OrderVO;
import com.myxhs.order.entity.*;
import com.myxhs.order.feign.CouponFeignClient;
import com.myxhs.order.feign.InventoryFeignClient;
import com.myxhs.order.feign.ProductFeignClient;
import com.myxhs.order.feign.UserFeignClient;
import com.myxhs.order.mapper.*;
import com.myxhs.order.repository.OrderNoMappingRepository;
import com.myxhs.order.repository.PaymentRepository;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：OrderMapper, OrderItemMapper, OrderSnapshotMapper, LocalMessageMapper,
 * OrderNoMappingRepository, OrderTransactionService, OrderEventService, RocketMQTemplate,
 * StringRedisTemplate, ObjectMapper, InventoryFeignClient, CouponFeignClient
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderSnapshotMapper snapshotMapper;
    @Mock
    private LocalMessageMapper localMessageMapper;
    @Mock
    private OrderNoMappingRepository orderNoMappingRepository;
    @Mock
    private OrderTransactionService transactionService;
    @Mock
    private OrderEventService orderEventService;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private InventoryFeignClient inventoryFeignClient;
    @Mock
    private CouponFeignClient couponFeignClient;
    @Mock
    private ProductFeignClient productFeignClient;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private OrderNotificationPublisher orderNotificationPublisher;

    private ObjectMapper objectMapper;
    private OrderService orderService;

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 123456L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // 使用构造函数注入 mock 对象（Lombok @RequiredArgsConstructor 生成）
        orderService = new OrderService(
                orderMapper, orderItemMapper, snapshotMapper, localMessageMapper,
                orderNoMappingRepository, transactionService,
                orderEventService,
                rocketMQTemplate, stringRedisTemplate, objectMapper,
                inventoryFeignClient, couponFeignClient, productFeignClient, userFeignClient,
                businessMetrics, orderNotificationPublisher
        );

        // resolveAddressSnapshot 需返回有效地址，否则下单测试会抛 ADDRESS_NOT_FOUND
        com.myxhs.order.dto.UserAddressDTO address = new com.myxhs.order.dto.UserAddressDTO();
        address.setReceiverName("张三");
        address.setReceiverPhone("13800138001");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园路1号");
        when(userFeignClient.getAddress(anyLong(), anyLong())).thenReturn(R.ok(address));
    }

    // ==================== 创建订单 ====================

    @Test
    @DisplayName("创建订单 - 幂等拒绝：bizIdentifier 已存在")
    void createOrder_idempotentReject() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
                .thenReturn(false);

        OrderCreateRequest request = buildCreateRequest("biz-001");
        assertThatThrownBy(() -> orderService.createOrder(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请勿重复下单");
    }

    @Test
    @DisplayName("创建订单 - 分布式锁获取失败")
    void createOrder_lockAcquireFailed() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 幂等键设置成功
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:idempotent:"), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
                .thenReturn(true);
        // 分布式锁获取失败
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:create:lock:"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);
        // 释放幂等键
        when(stringRedisTemplate.delete(startsWith("myxhs:order:idempotent:"))).thenReturn(true);

        OrderCreateRequest request = buildCreateRequest("biz-002");
        assertThatThrownBy(() -> orderService.createOrder(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("操作过于频繁");
    }

    @Test
    @DisplayName("创建订单 - 事务消息发送失败")
    void createOrder_transactionMessageFailed() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 幂等键成功
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:idempotent:"), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
                .thenReturn(true);
        // 分布式锁成功
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:create:lock:"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        // Redis INCR（订单号序列号）
        when(valueOperations.increment(startsWith("order:seq:"))).thenReturn(1L);
        when(stringRedisTemplate.expire(anyString(), eq(48L), eq(TimeUnit.HOURS))).thenReturn(true);
        // Lua 释放锁
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        // 删除幂等键
        when(stringRedisTemplate.delete(startsWith("myxhs:order:idempotent:"))).thenReturn(true);

        SkuInfoDTO sku = new SkuInfoDTO();
        sku.setId(10001L);
        sku.setSpuId(10001L);
        sku.setName("Mock商品-10001");
        sku.setPrice(new BigDecimal("99.00"));
        sku.setSpuStatus(1);
        when(productFeignClient.batchGetSkuDetails(anyList()))
                .thenReturn(R.ok(Collections.singletonList(sku)));
        when(inventoryFeignClient.queryStock(10001L))
                .thenReturn(R.ok(Collections.singletonMap("availableStock", 2)));

        // 事务消息发送返回非 SEND_OK
        TransactionSendResult sendResult = new TransactionSendResult();
        sendResult.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(rocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TRANSACTION_TOPIC), any(), any()))
                .thenReturn(sendResult);

        OrderCreateRequest request = buildCreateRequest("biz-003");
        assertThatThrownBy(() -> orderService.createOrder(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("消息发送异常");
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询订单详情 - 订单不存在")
    void getOrderDetail_notFound() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> orderService.getOrderDetail(USER_ID, ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.ORDER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("查询订单详情 - 正常查询")
    void getOrderDetail_success() {
        Order order = buildOrder();
        OrderItem item = buildOrderItem();
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(item));

        OrderVO vo = orderService.getOrderDetail(USER_ID, ORDER_ID);
        assertThat(vo).isNotNull();
        assertThat(vo.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(vo.getOrderNo()).isEqualTo("ORD20250101000000001");
        assertThat(vo.getStatus()).isEqualTo(0);
        assertThat(vo.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("查询用户订单列表 - 无筛选")
    void getUserOrders_noFilter() {
        Order order = buildOrder();
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(order));

        List<OrderVO> list = orderService.getUserOrders(USER_ID, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getOrderNo()).isEqualTo("ORD20250101000000001");
    }

    @Test
    @DisplayName("查询用户订单列表 - 按状态筛选")
    void getUserOrders_filterByStatus() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<OrderVO> list = orderService.getUserOrders(USER_ID, 3);
        assertThat(list).isEmpty();
    }

    // ==================== 通过订单号查询 ====================

    @Test
    @DisplayName("通过订单号查询 - 映射表中无记录")
    void getOrderByOrderNo_notFound() {
        when(orderNoMappingRepository.selectByOrderNo("ORD-NOT-FOUND")).thenReturn(null);

        assertThatThrownBy(() -> orderService.getOrderByOrderNo(1L, "ORD-NOT-FOUND"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    @DisplayName("通过订单号查询 - 正常查询")
    void getOrderByOrderNo_success() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderNo("ORD20250101000000001");
        mapping.setUserId(USER_ID);
        mapping.setOrderId(ORDER_ID);
        when(orderNoMappingRepository.selectByOrderNo("ORD20250101000000001")).thenReturn(mapping);

        Order order = buildOrder();
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        OrderVO vo = orderService.getOrderByOrderNo(USER_ID, "ORD20250101000000001");
        assertThat(vo).isNotNull();
        assertThat(vo.getOrderId()).isEqualTo(ORDER_ID);
    }

    // ==================== 取消订单 ====================

    @Test
    @DisplayName("取消订单 - 订单不存在")
    void cancelOrder_notFound() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.ORDER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("取消订单 - 状态不允许取消（非待付款）")
    void cancelOrder_wrongStatus() {
        Order order = buildOrder();
        order.setStatus(1); // 已付款，不能取消
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能取消待付款的订单");
    }

    @Test
    @DisplayName("取消订单 - 正常取消")
    void cancelOrder_success() {
        Order order = buildOrder();
        order.setStatus(0); // 待付款
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        // Event Sourcing: appendEvent 会被调用
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_CANCELLED), anyMap());
        // Feign 调用返回成功
        when(inventoryFeignClient.releaseStock(anyMap()))
                .thenReturn(R.ok());
        // 快照写入成功
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        // 删除缓存
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThatCode(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .doesNotThrowAnyException();

        verify(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_CANCELLED), anyMap());
    }

    @Test
    @DisplayName("取消订单 - appendEvent 抛出异常（乐观锁冲突）")
    void cancelOrder_appendEventFailed() {
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        // Event Sourcing: appendEvent 抛出异常模拟乐观锁冲突
        doThrow(new RuntimeException("乐观锁冲突")).when(orderEventService)
                .appendEvent(eq(order), eq(OrderEventService.EVENT_CANCELLED), anyMap());

        assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("乐观锁冲突");
    }

    // ==================== 确认收货 ====================

    @Test
    @DisplayName("确认收货 - 正常确认")
    void confirmReceive_success() {
        Order order = buildOrder();
        order.setStatus(2); // 已发货
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_COMPLETED), anyMap());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThatCode(() -> orderService.confirmReceive(USER_ID, ORDER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("确认收货 - 状态不允许（非已发货）")
    void confirmReceive_wrongStatus() {
        Order order = buildOrder();
        order.setStatus(0); // 待付款
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        assertThatThrownBy(() -> orderService.confirmReceive(USER_ID, ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能确认已发货的订单");
    }

    // ==================== 支付成功回调 ====================

    @Test
    @DisplayName("支付成功回调 - 正常更新")
    void onPaymentSuccess_success() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildOrder());
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(any(Order.class), eq(OrderEventService.EVENT_PAID), anyMap());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        boolean result = orderService.onPaymentSuccess(ORDER_ID, USER_ID);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("支付成功回调 - 状态不是待付款（已取消或已支付）")
    void onPaymentSuccess_wrongStatus() {
        Order order = buildOrder();
        order.setStatus(4); // 已取消
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        boolean result = orderService.onPaymentSuccess(ORDER_ID, USER_ID);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("支付成功回调 - userId 为 null 时通过映射表反查")
    void onPaymentSuccess_userIdNull_lookupMapping() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildOrder());
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(any(Order.class), eq(OrderEventService.EVENT_PAID), anyMap());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        boolean result = orderService.onPaymentSuccess(ORDER_ID, null);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("支付成功回调 - userId 为 null 且映射表无记录")
    void onPaymentSuccess_userIdNull_noMapping() {
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(null);

        boolean result = orderService.onPaymentSuccess(ORDER_ID, null);
        assertThat(result).isFalse();
    }

    // ==================== 超时关单 ====================

    @Test
    @DisplayName("超时关单 - 订单已支付（幂等跳过）")
    void closeTimeoutOrder_alreadyPaid() {
        Order order = buildOrder();
        order.setStatus(1); // 已支付
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        orderService.closeTimeoutOrder(ORDER_ID, USER_ID);
        // 不应该调用 appendEvent
        verify(orderEventService, never()).appendEvent(any(Order.class), anyString(), any());
    }

    @Test
    @DisplayName("超时关单 - 正常关单")
    void closeTimeoutOrder_success() {
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_TIMEOUT_CANCELLED), anyMap());
        when(inventoryFeignClient.releaseStock(anyMap())).thenReturn(R.ok());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        orderService.closeTimeoutOrder(ORDER_ID, USER_ID);

        verify(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_TIMEOUT_CANCELLED), anyMap());
        verify(inventoryFeignClient).releaseStock(anyMap());
    }

    // ==================== 退款成功 ====================

    @Test
    @DisplayName("退款成功 - 正常退款")
    void onRefundSuccess_success() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);

        Order order = buildOrder();
        order.setStatus(1); // 已支付
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_REFUNDED), anyMap());
        when(inventoryFeignClient.releaseStock(anyMap())).thenReturn(R.ok());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isTrue();
    }

    @Test
    @DisplayName("退款成功 - 映射表无记录（返回失败）")
    void onRefundSuccess_noMapping() {
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(null);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isFalse();
        verify(orderMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("退款成功 - 订单状态不是已付款（返回失败）")
    void onRefundSuccess_wrongStatus() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isFalse();
        verify(orderEventService, never()).appendEvent(any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("退款成功 - 已完成(收货后)状态退款收敛")
    void onRefundSuccess_completed() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        Order order = buildOrder();
        order.setStatus(3); // 已完成
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_REFUNDED), anyMap());
        when(inventoryFeignClient.releaseStock(anyMap())).thenReturn(R.ok());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isTrue();
        verify(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_REFUNDED), anyMap());
    }

    @Test
    @DisplayName("退款成功 - 已退款状态幂等成功")
    void onRefundSuccess_alreadyRefunded() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        Order order = buildOrder();
        order.setStatus(5);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isTrue();
        verify(orderEventService, never()).appendEvent(any(), anyString(), anyMap());
    }

    // ==================== 查询支付金额 ====================

    @Test
    @DisplayName("查询支付金额 - 正常查询")
    void getOrderPayAmount_success() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setUserId(USER_ID);
        mapping.setOrderId(ORDER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);

        Order order = buildOrder();
        order.setPayAmount(new BigDecimal("198.00"));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        BigDecimal amount = orderService.getOrderPayAmount(ORDER_ID);
        assertThat(amount).isEqualByComparingTo("198.00");
    }

    @Test
    @DisplayName("查询支付金额 - 映射表无记录")
    void getOrderPayAmount_notFound() {
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(null);

        // 订单不存在时返回 null，供支付侧理解为不可支付，而非抛异常（与 getOrderStatus 语义一致）
        assertThat(orderService.getOrderPayAmount(ORDER_ID)).isNull();
    }

    // ==================== 辅助方法 ====================

    private OrderCreateRequest buildCreateRequest(String bizIdentifier) {
        OrderCreateRequest request = new OrderCreateRequest();
        OrderCreateRequest.SkuItem item = new OrderCreateRequest.SkuItem();
        item.setSkuId(10001L);
        item.setQuantity(2);
        request.setSkuItems(Collections.singletonList(item));
        request.setCouponId(null);
        request.setAddressId(1L);
        request.setRemark("测试订单");
        request.setBizIdentifier(bizIdentifier);
        return request;
    }

    private Order buildOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setOrderNo("ORD20250101000000001");
        order.setTotalAmount(new BigDecimal("198.00"));
        order.setPayAmount(new BigDecimal("198.00"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setStatus(0);
        order.setRemark("测试订单");
        order.setAddressSnapshot("{\"name\":\"测试\"}");
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    private OrderItem buildOrderItem() {
        OrderItem item = new OrderItem();
        item.setId(1L);
        item.setOrderId(ORDER_ID);
        item.setUserId(USER_ID);
        item.setSkuId(10001L);
        item.setSpuId(10001L);
        item.setSkuName("Mock商品-10001");
        item.setSkuImage("https://img.mock.com/sku/10001.jpg");
        item.setPrice(new BigDecimal("99.00"));
        item.setQuantity(2);
        item.setTotalAmount(new BigDecimal("198.00"));
        return item;
    }
}
