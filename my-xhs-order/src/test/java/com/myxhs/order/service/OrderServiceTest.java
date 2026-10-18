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
import com.myxhs.order.listener.OrderTerminationListener;
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
    private com.myxhs.order.mapper.SubOrderMapper subOrderMapper;
    @Mock
    private com.myxhs.order.service.SubOrderService subOrderService;
    @Mock
    private com.myxhs.order.repository.AftersaleRepository aftersaleRepository;
    @Mock
    private OrderEventService orderEventService;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private RocketMQTemplate orderTerminationRocketMQTemplate;
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
                subOrderMapper, subOrderService, aftersaleRepository,
                orderTerminationRocketMQTemplate,
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

    @Test
    @DisplayName("C-006：创建事务消息载荷含 source（下单清车联动；借发送异常路径捕获消息体）")
    void createOrder_payloadContainsSource() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:idempotent:"), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
                .thenReturn(true);
        when(valueOperations.setIfAbsent(
                startsWith("myxhs:order:create:lock:"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(valueOperations.increment(startsWith("order:seq:"))).thenReturn(1L);
        when(stringRedisTemplate.expire(anyString(), eq(48L), eq(TimeUnit.HOURS))).thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
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

        TransactionSendResult sendResult = new TransactionSendResult();
        sendResult.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT); // 触发既有异常收口（消息体已捕获）
        when(rocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TRANSACTION_TOPIC), any(), any()))
                .thenReturn(sendResult);

        OrderCreateRequest request = buildCreateRequest("biz-cart-006");
        request.setSource("CART");

        assertThatThrownBy(() -> orderService.createOrder(USER_ID, request))
                .isInstanceOf(BizException.class);

        org.mockito.ArgumentCaptor<org.springframework.messaging.Message<String>> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(
                eq(OrderService.ORDER_TRANSACTION_TOPIC), captor.capture(), any());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.get("source").asText()).isEqualTo("CART");
        assertThat(payload.get("skuItems").size()).isEqualTo(1);
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
        // 04B：终结事务消息发送 → 模拟监听器执行本地事务（回写 outcome=TERMINATED）并返回 SEND_OK
        when(orderTerminationRocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any()))
                .thenAnswer(invocation -> {
                    OrderTerminationListener.OrderTerminationContext context = invocation.getArgument(2);
                    context.setOutcome(OrderTerminationService.Outcome.TERMINATED);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setSendStatus(SendStatus.SEND_OK);
                    return result;
                });
        // 快照写入成功
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        // 删除缓存
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);
        // 本地消息标记成功（防补发 Job 双投）
        when(localMessageMapper.markSuccessByTransactionIdAndType(anyString(), anyLong(), anyString()))
                .thenReturn(1);

        assertThatCode(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .doesNotThrowAnyException();

        verify(orderTerminationRocketMQTemplate).sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any());
    }

    @Test
    @DisplayName("取消订单 - 本地事务回滚且未收敛（状态已变化）")
    void cancelOrder_localTransactionRollback() {
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        // 04B：事务消息发送成功但监听器本地事务回滚（outcome 不回写=保持 null）→ 回读仍为待付款 → 状态已变化
        when(orderTerminationRocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any()))
                .thenAnswer(invocation -> {
                    TransactionSendResult result = new TransactionSendResult();
                    result.setSendStatus(SendStatus.SEND_OK);
                    return result;
                });

        assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单状态已变化");
    }

    // ==================== 支付失败回调（第五条终结路径）====================

    @Test
    @DisplayName("支付失败回调 - 正常终结（04B 统一入口：状态 0→4 + 终结事件）")
    void onPaymentFailed_success() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(orderTerminationRocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any()))
                .thenAnswer(invocation -> {
                    OrderTerminationListener.OrderTerminationContext context = invocation.getArgument(2);
                    context.setOutcome(OrderTerminationService.Outcome.TERMINATED);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setSendStatus(SendStatus.SEND_OK);
                    return result;
                });
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);
        when(localMessageMapper.markSuccessByTransactionIdAndType(anyString(), anyLong(), anyString()))
                .thenReturn(1);

        assertThatCode(() -> orderService.onPaymentFailed(ORDER_ID))
                .doesNotThrowAnyException();

        verify(orderTerminationRocketMQTemplate).sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any());
    }

    @Test
    @DisplayName("支付失败回调 - 映射缺失：幂等跳过")
    void onPaymentFailed_mappingMissing() {
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(null);

        assertThatCode(() -> orderService.onPaymentFailed(ORDER_ID))
                .doesNotThrowAnyException();

        verify(orderTerminationRocketMQTemplate, never()).sendMessageInTransaction(anyString(), any(), any());
    }

    // ==================== 确认收货（ITER-001：子单维度） ====================

    @Test
    @DisplayName("确认收货 - 正常确认（子单 2→3 + 父单聚合）")
    void confirmReceive_success() {
        com.myxhs.order.entity.SubOrder sub = new com.myxhs.order.entity.SubOrder();
        sub.setId(9001L);
        sub.setOrderId(ORDER_ID);
        sub.setSubOrderNo("ORD-X-1");
        sub.setUserId(USER_ID);
        sub.setStatus(2); // 已发货
        when(subOrderMapper.selectBySubOrderNo("ORD-X-1", USER_ID)).thenReturn(sub);
        when(subOrderMapper.markCompleted(9001L, USER_ID)).thenReturn(1);
        when(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).thenReturn(true);
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThatCode(() -> orderService.confirmReceive(USER_ID, "ORD-X-1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("确认收货 - 状态不允许（非已发货子单）")
    void confirmReceive_wrongStatus() {
        com.myxhs.order.entity.SubOrder sub = new com.myxhs.order.entity.SubOrder();
        sub.setId(9002L);
        sub.setOrderId(ORDER_ID);
        sub.setSubOrderNo("ORD-X-2");
        sub.setUserId(USER_ID);
        sub.setStatus(1); // 待发货
        when(subOrderMapper.selectBySubOrderNo("ORD-X-2", USER_ID)).thenReturn(sub);

        assertThatThrownBy(() -> orderService.confirmReceive(USER_ID, "ORD-X-2"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能确认已发货的子订单");
    }

    // ==================== 发货（ITER-001：子单维度） ====================

    @Test
    @DisplayName("发货 - 正常发货（子单 1→2 + 父单聚合 + 子单通知）")
    void deliverOrder_success() {
        com.myxhs.order.entity.SubOrder sub = new com.myxhs.order.entity.SubOrder();
        sub.setId(9001L);
        sub.setOrderId(ORDER_ID);
        sub.setSubOrderNo("ORD-X-1");
        sub.setUserId(USER_ID);
        sub.setStatus(1); // 待发货
        when(subOrderMapper.selectBySubOrderNo("ORD-X-1", USER_ID)).thenReturn(sub);
        when(orderItemMapper.selectBySubOrderId(9001L, USER_ID)).thenReturn(Collections.emptyList());
        when(subOrderMapper.markDelivered(9001L, USER_ID)).thenReturn(1);
        when(subOrderService.recomputeParentStatus(USER_ID, ORDER_ID)).thenReturn(true);
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThatCode(() -> orderService.deliverOrder(USER_ID, "ORD-X-1", "顺丰", "SF123"))
                .doesNotThrowAnyException();
        verify(orderNotificationPublisher).publishStatusChanged(
                eq(USER_ID), eq(ORDER_ID), any(), eq("子订单已发货，请注意查收"), eq("顺丰 SF123"));
    }

    @Test
    @DisplayName("发货 - 拦截（决策 2）：子单商品存在进行中的仅退款售后 → 拒绝")
    void deliverOrder_blockedByActiveRefundAftersale() {
        com.myxhs.order.entity.SubOrder sub = new com.myxhs.order.entity.SubOrder();
        sub.setId(9001L);
        sub.setOrderId(ORDER_ID);
        sub.setSubOrderNo("ORD-X-1");
        sub.setUserId(USER_ID);
        sub.setStatus(1);
        when(subOrderMapper.selectBySubOrderNo("ORD-X-1", USER_ID)).thenReturn(sub);

        OrderItem item = new OrderItem();
        item.setId(7001L);
        item.setSubOrderId(9001L);
        item.setUserId(USER_ID);
        when(orderItemMapper.selectBySubOrderId(9001L, USER_ID)).thenReturn(java.util.List.of(item));

        com.myxhs.order.entity.Aftersale aftersale = new com.myxhs.order.entity.Aftersale();
        aftersale.setOrderId(ORDER_ID);
        aftersale.setItemId(7001L);
        aftersale.setType(com.myxhs.order.entity.Aftersale.TYPE_REFUND_ONLY);
        aftersale.setStatus(com.myxhs.order.entity.Aftersale.STATUS_APPLIED);
        when(aftersaleRepository.listByOrder(ORDER_ID)).thenReturn(java.util.List.of(aftersale));

        assertThatThrownBy(() -> orderService.deliverOrder(USER_ID, "ORD-X-1", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("退款申请");
        verify(subOrderMapper, never()).markDelivered(anyLong(), anyLong());
    }

    @Test
    @DisplayName("发货 - 状态不允许（非待发货子单）")
    void deliverOrder_wrongStatus() {
        com.myxhs.order.entity.SubOrder sub = new com.myxhs.order.entity.SubOrder();
        sub.setId(9002L);
        sub.setOrderId(ORDER_ID);
        sub.setSubOrderNo("ORD-X-2");
        sub.setUserId(USER_ID);
        sub.setStatus(0); // 待付款
        when(subOrderMapper.selectBySubOrderNo("ORD-X-2", USER_ID)).thenReturn(sub);

        assertThatThrownBy(() -> orderService.deliverOrder(USER_ID, "ORD-X-2", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能对已付款（待发货）的子订单执行发货");
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
    @DisplayName("超时关单 - 正常关单（04B：终结事务消息 + 本地事务）")
    void closeTimeoutOrder_success() {
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        // 04B：终结事务消息发送 → 模拟监听器回写 outcome=TERMINATED 并返回 SEND_OK
        when(orderTerminationRocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any()))
                .thenAnswer(invocation -> {
                    OrderTerminationListener.OrderTerminationContext context = invocation.getArgument(2);
                    context.setOutcome(OrderTerminationService.Outcome.TERMINATED);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setSendStatus(SendStatus.SEND_OK);
                    return result;
                });
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);
        when(localMessageMapper.markSuccessByTransactionIdAndType(anyString(), anyLong(), anyString()))
                .thenReturn(1);

        orderService.closeTimeoutOrder(ORDER_ID, USER_ID);

        verify(orderTerminationRocketMQTemplate).sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any());
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
    @DisplayName("退款成功 - 已发货(2)状态退款收敛（决策 1：守卫放宽 {1,2,3}，修复 08 §5.2 缺口）")
    void onRefundSuccess_shipped() {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(ORDER_ID);
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderId(ORDER_ID)).thenReturn(mapping);
        Order order = buildOrder();
        order.setStatus(2); // 已发货
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_REFUNDED), anyMap());
        when(inventoryFeignClient.releaseStock(anyMap())).thenReturn(R.ok());
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);

        assertThat(orderService.onRefundSuccess(ORDER_ID)).isTrue();
        verify(orderEventService).appendEvent(eq(order), eq(OrderEventService.EVENT_REFUNDED), anyMap());
        // 拆单（ITER-001）：全退收敛——全部子单置退款
        verify(subOrderService).refundAll(USER_ID, ORDER_ID);
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

    // ==================== 商家侧查询（C-004 P1） ====================

    @Test
    @DisplayName("商家子单详情 - 服务端路由解析（subOrderNo→orderNo→映射表→userId→路由读）+ 行明细")
    void sellerSubOrderDetailResolvesRoute() {
        com.myxhs.order.entity.OrderNoMapping mapping = new com.myxhs.order.entity.OrderNoMapping();
        mapping.setUserId(USER_ID);
        mapping.setOrderId(ORDER_ID);
        when(orderNoMappingRepository.selectByOrderNo("ORDER-1001")).thenReturn(mapping);

        SubOrder sub = new SubOrder();
        sub.setId(9001L);
        sub.setOrderId(ORDER_ID);
        sub.setOrderNo("ORDER-1001");
        sub.setSubOrderNo("ORDER-1001-1");
        sub.setUserId(USER_ID);
        sub.setSellerId(3001L);
        sub.setStatus(2);
        sub.setTotalAmount(new java.math.BigDecimal("199.00"));
        sub.setDiscountAmount(new java.math.BigDecimal("9.00"));
        sub.setPayAmount(new java.math.BigDecimal("190.00"));
        when(subOrderMapper.selectBySubOrderNo("ORDER-1001-1", USER_ID)).thenReturn(sub);

        OrderItem item = new OrderItem();
        item.setId(8001L);
        item.setSubOrderId(9001L);
        item.setSkuId(1L);
        item.setQuantity(2);
        when(orderItemMapper.selectBySubOrderId(9001L, USER_ID)).thenReturn(java.util.List.of(item));

        OrderVO.SubOrderVO vo = orderService.getSellerSubOrderDetail("ORDER-1001-1");

        assertThat(vo.getSubOrderNo()).isEqualTo("ORDER-1001-1");
        assertThat(vo.getSellerId()).isEqualTo(3001L);
        assertThat(vo.getStatus()).isEqualTo(2);
        assertThat(vo.getItems()).hasSize(1);
        // 路由解析：映射表按 orderNo 查询（不带分片键的商家路径）
        verify(orderNoMappingRepository).selectByOrderNo("ORDER-1001");
        verify(subOrderMapper).selectBySubOrderNo("ORDER-1001-1", USER_ID);
    }

    @Test
    @DisplayName("商家子单详情 - 子单号格式非法 → PARAM_INVALID")
    void sellerSubOrderDetailBadFormat() {
        assertThatThrownBy(() -> orderService.getSellerSubOrderDetail("NODASHFORMATLESS"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子单号格式非法");
        assertThatThrownBy(() -> orderService.getSellerSubOrderDetail("-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子单号格式非法");
    }

    @Test
    @DisplayName("商家子单详情 - 映射缺失 → 明确报错（不静默、不 scatter）")
    void sellerSubOrderDetailMappingMissing() {
        when(orderNoMappingRepository.selectByOrderNo("ORDER-9999")).thenReturn(null);

        assertThatThrownBy(() -> orderService.getSellerSubOrderDetail("ORDER-9999-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("路由信息缺失");
        verify(subOrderMapper, never()).selectBySubOrderNo(anyString(), anyLong());
    }

    @Test
    @DisplayName("商家子单详情 - 子单不存在 → ORDER_NOT_FOUND")
    void sellerSubOrderDetailSubMissing() {
        com.myxhs.order.entity.OrderNoMapping mapping = new com.myxhs.order.entity.OrderNoMapping();
        mapping.setUserId(USER_ID);
        when(orderNoMappingRepository.selectByOrderNo("ORDER-1001")).thenReturn(mapping);
        when(subOrderMapper.selectBySubOrderNo("ORDER-1001-1", USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.getSellerSubOrderDetail("ORDER-1001-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子单不存在");
    }

    @Test
    @DisplayName("子单扫描 - snake_case 行映射（回填口径）")
    void scanSubOrdersMapsRows() {
        SubOrder sub = new SubOrder();
        sub.setId(1L);
        sub.setOrderId(ORDER_ID);
        sub.setOrderNo("ORDER-1001");
        sub.setSubOrderNo("ORDER-1001-1");
        sub.setUserId(USER_ID);
        sub.setSellerId(3001L);
        sub.setStatus(2);
        sub.setTotalAmount(new java.math.BigDecimal("199.00"));
        when(subOrderMapper.scanByCursor(0L, 500)).thenReturn(java.util.List.of(sub));

        java.util.List<java.util.Map<String, Object>> rows = orderService.scanSubOrders(0L, 500);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("sub_order_no", "ORDER-1001-1");
        assertThat(rows.get(0)).containsEntry("seller_id", 3001L);
        assertThat(rows.get(0)).containsEntry("id", 1L);
    }

    @Test
    @DisplayName("C-006：终结事件载荷含 items + eventVersion=2（购物车回补联动）")
    void terminationPayloadContainsItems() throws Exception {
        Order order = buildOrder();
        order.setStatus(0);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        OrderItem item = new OrderItem();
        item.setSkuId(1L);
        item.setQuantity(2);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(item));
        when(orderTerminationRocketMQTemplate.sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), any(), any()))
                .thenAnswer(invocation -> {
                    OrderTerminationListener.OrderTerminationContext context = invocation.getArgument(2);
                    context.setOutcome(OrderTerminationService.Outcome.TERMINATED);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setSendStatus(SendStatus.SEND_OK);
                    return result;
                });
        when(snapshotMapper.insert((OrderSnapshot) any())).thenReturn(1);
        when(stringRedisTemplate.delete(startsWith("myxhs:order:info:"))).thenReturn(true);
        when(localMessageMapper.markSuccessByTransactionIdAndType(anyString(), anyLong(), anyString()))
                .thenReturn(1);

        orderService.cancelOrder(USER_ID, ORDER_ID);

        org.mockito.ArgumentCaptor<org.springframework.messaging.Message<String>> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(orderTerminationRocketMQTemplate).sendMessageInTransaction(
                eq(OrderService.ORDER_TERMINATION_TOPIC), captor.capture(), any());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.get("eventVersion").asInt()).isEqualTo(2);
        assertThat(payload.get("items")).isNotNull();
        assertThat(payload.get("items").size()).isEqualTo(1);
        assertThat(payload.get("items").get(0).get("skuId").asLong()).isEqualTo(1L);
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }
}
