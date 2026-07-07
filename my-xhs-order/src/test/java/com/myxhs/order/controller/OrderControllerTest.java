package com.myxhs.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.exception.GlobalExceptionHandler;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.dto.request.PayRequest;
import com.myxhs.order.dto.response.OrderVO;
import com.myxhs.order.entity.Payment;
import com.myxhs.order.feign.PaymentFeignClient;
import com.myxhs.order.service.MockPayService;
import com.myxhs.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderController 全链路单元测试
 * <p>
 * 使用纯 Mockito + Standalone MockMvc 模式，不启动 Spring 容器。
 * 这样完全避免了 common 包中的 Redis/DB/XXL-Job 等自动配置依赖。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OrderService orderService;

    @Mock
    private MockPayService mockPayService;

    @Mock
    private PaymentFeignClient paymentFeignClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 123456L;
    private static final String USER_ID_HEADER = "X-User-Id";

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderService, mockPayService, paymentFeignClient);
        // 设置 pay.type=mock（默认行为）
        ReflectionTestUtils.setField(controller, "payType", "mock");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ==================== 创建订单 ====================

    @Test
    @DisplayName("POST /api/order/create - 创建成功")
    void createOrder_success() throws Exception {
        OrderVO vo = buildOrderVO();
        when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class)))
                .thenReturn(vo);

        String body = objectMapper.writeValueAsString(buildCreateRequest("biz-001"));

        mockMvc.perform(post("/api/order/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.orderNo").value("ORD20250101000000001"));
    }

    @Test
    @DisplayName("POST /api/order/create - 参数校验失败（bizIdentifier 为空）")
    void createOrder_validationFail() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(Collections.singletonList(createSkuItem(10001L, 1)));
        request.setAddressId(1L);
        // bizIdentifier 未设置

        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/order/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("POST /api/order/create - 业务异常（重复下单）")
    void createOrder_bizException() throws Exception {
        when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class)))
                .thenThrow(new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复下单"));

        String body = objectMapper.writeValueAsString(buildCreateRequest("biz-002"));

        mockMvc.perform(post("/api/order/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.IDEMPOTENT_REJECT.getCode()))
                .andExpect(jsonPath("$.message").value("请勿重复下单"));
    }

    // ==================== 订单详情 ====================

    @Test
    @DisplayName("GET /api/order/{orderId} - 查询成功")
    void getOrderDetail_success() throws Exception {
        when(orderService.getOrderDetail(USER_ID, ORDER_ID))
                .thenReturn(buildOrderVO());

        mockMvc.perform(get("/api/order/{orderId}", ORDER_ID)
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID));
    }

    @Test
    @DisplayName("GET /api/order/{orderId} - 订单不存在")
    void getOrderDetail_notFound() throws Exception {
        when(orderService.getOrderDetail(USER_ID, ORDER_ID))
                .thenThrow(new BizException(ResultCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/order/{orderId}", ORDER_ID)
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    // ==================== 订单列表 ====================

    @Test
    @DisplayName("GET /api/order/list - 查询全部订单")
    void getUserOrders_all() throws Exception {
        when(orderService.getUserOrders(USER_ID, null))
                .thenReturn(Collections.singletonList(buildOrderVO()));

        mockMvc.perform(get("/api/order/list")
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID));
    }

    @Test
    @DisplayName("GET /api/order/list - 按状态筛选")
    void getUserOrders_filterByStatus() throws Exception {
        when(orderService.getUserOrders(USER_ID, 0))
                .thenReturn(Collections.singletonList(buildOrderVO()));

        mockMvc.perform(get("/api/order/list")
                        .header(USER_ID_HEADER, USER_ID)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 按订单号查询 ====================

    @Test
    @DisplayName("GET /api/order/by-order-no/{orderNo} - 查询成功")
    void getOrderByOrderNo_success() throws Exception {
        when(orderService.getOrderByOrderNo("ORD20250101000000001"))
                .thenReturn(buildOrderVO());

        mockMvc.perform(get("/api/order/by-order-no/{orderNo}", "ORD20250101000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("ORD20250101000000001"));
    }

    @Test
    @DisplayName("GET /api/order/by-order-no/{orderNo} - 订单不存在")
    void getOrderByOrderNo_notFound() throws Exception {
        when(orderService.getOrderByOrderNo("ORD-NOT-EXIST"))
                .thenThrow(new BizException(ResultCode.ORDER_NOT_FOUND, "订单不存在"));

        mockMvc.perform(get("/api/order/by-order-no/{orderNo}", "ORD-NOT-EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    // ==================== 取消订单 ====================

    @Test
    @DisplayName("POST /api/order/cancel - 取消成功")
    void cancelOrder_success() throws Exception {
        doNothing().when(orderService).cancelOrder(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/order/cancel")
                        .header(USER_ID_HEADER, USER_ID)
                        .param("orderId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("POST /api/order/cancel - 订单不存在")
    void cancelOrder_notFound() throws Exception {
        doThrow(new BizException(ResultCode.ORDER_NOT_FOUND))
                .when(orderService).cancelOrder(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/order/cancel")
                        .header(USER_ID_HEADER, USER_ID)
                        .param("orderId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    // ==================== 确认收货 ====================

    @Test
    @DisplayName("POST /api/order/confirm - 确认成功")
    void confirmReceive_success() throws Exception {
        doNothing().when(orderService).confirmReceive(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/order/confirm")
                        .header(USER_ID_HEADER, USER_ID)
                        .param("orderId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Mock 支付 ====================

    @Test
    @DisplayName("POST /api/order/pay/create - Mock 支付成功")
    void createPayment_success() throws Exception {
        Payment payment = buildPayment();
        when(mockPayService.createPayment(eq(USER_ID), any(PayRequest.class)))
                .thenReturn(payment);

        PayRequest payRequest = new PayRequest();
        payRequest.setOrderId(ORDER_ID);
        payRequest.setPayType(1);
        String body = objectMapper.writeValueAsString(payRequest);

        mockMvc.perform(post("/api/order/pay/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paymentNo").value("MOCK_PAY_1234567890"));
    }

    @Test
    @DisplayName("POST /api/order/pay/create - 参数校验失败")
    void createPayment_validationFail() throws Exception {
        PayRequest payRequest = new PayRequest();
        // orderId 和 payType 都未设置
        String body = objectMapper.writeValueAsString(payRequest);

        mockMvc.perform(post("/api/order/pay/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/order/pay/create - 订单不存在")
    void createPayment_orderNotFound() throws Exception {
        when(mockPayService.createPayment(eq(USER_ID), any(PayRequest.class)))
                .thenThrow(new BizException(ResultCode.ORDER_NOT_FOUND));

        PayRequest payRequest = new PayRequest();
        payRequest.setOrderId(ORDER_ID);
        payRequest.setPayType(1);
        String body = objectMapper.writeValueAsString(payRequest);

        mockMvc.perform(post("/api/order/pay/create")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    // ==================== 查询支付状态 ====================

    @Test
    @DisplayName("GET /api/order/pay/status/{orderId} - Mock 模式查询成功")
    void getPaymentStatus_success() throws Exception {
        Payment payment = buildPayment();
        when(mockPayService.getPaymentByOrderId(ORDER_ID)).thenReturn(payment);

        mockMvc.perform(get("/api/order/pay/status/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 支付回调 ====================

    @Test
    @DisplayName("POST /api/order/pay-success - 支付成功回调")
    void notifyPaySuccess() throws Exception {
        when(orderService.onPaymentSuccess(ORDER_ID, null)).thenReturn(true);

        mockMvc.perform(post("/api/order/pay-success")
                        .param("orderId", ORDER_ID.toString())
                        .param("tradeNo", "TRADE_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("POST /api/order/pay-success - 支付成功但状态更新失败")
    void notifyPaySuccess_updateFailed() throws Exception {
        when(orderService.onPaymentSuccess(ORDER_ID, null)).thenReturn(false);

        // 即使更新失败，接口仍返回 200（降级处理）
        mockMvc.perform(post("/api/order/pay-success")
                        .param("orderId", ORDER_ID.toString())
                        .param("tradeNo", "TRADE_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("POST /api/order/pay-fail - 支付失败回调")
    void notifyPayFail() throws Exception {
        mockMvc.perform(post("/api/order/pay-fail")
                        .param("orderId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 退款回调 ====================

    @Test
    @DisplayName("POST /api/order/refund-success - 退款成功回调")
    void notifyRefundSuccess() throws Exception {
        doNothing().when(orderService).onRefundSuccess(ORDER_ID);

        mockMvc.perform(post("/api/order/refund-success")
                        .param("orderId", ORDER_ID.toString())
                        .param("refundNo", "REFUND_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("POST /api/order/refund-fail - 退款失败回调")
    void notifyRefundFail() throws Exception {
        mockMvc.perform(post("/api/order/refund-fail")
                        .param("orderId", ORDER_ID.toString())
                        .param("refundNo", "REFUND_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 查询支付金额 ====================

    @Test
    @DisplayName("GET /api/order/pay-amount - 查询成功")
    void getOrderPayAmount_success() throws Exception {
        when(orderService.getOrderPayAmount(ORDER_ID))
                .thenReturn(new BigDecimal("198.00"));

        mockMvc.perform(get("/api/order/pay-amount")
                        .param("orderId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(198.00));
    }

    // ==================== 辅助方法 ====================

    private OrderCreateRequest buildCreateRequest(String bizIdentifier) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSkuItems(Collections.singletonList(createSkuItem(10001L, 2)));
        request.setAddressId(1L);
        request.setRemark("测试订单");
        request.setBizIdentifier(bizIdentifier);
        return request;
    }

    private OrderCreateRequest.SkuItem createSkuItem(Long skuId, int quantity) {
        OrderCreateRequest.SkuItem item = new OrderCreateRequest.SkuItem();
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }

    private OrderVO buildOrderVO() {
        OrderVO.OrderItemVO item = OrderVO.OrderItemVO.builder()
                .skuId(10001L)
                .skuName("Mock商品-10001")
                .skuImage("https://img.mock.com/sku/10001.jpg")
                .price(new BigDecimal("99.00"))
                .quantity(2)
                .totalAmount(new BigDecimal("198.00"))
                .build();

        return OrderVO.builder()
                .orderId(ORDER_ID)
                .orderNo("ORD20250101000000001")
                .totalAmount(new BigDecimal("198.00"))
                .payAmount(new BigDecimal("198.00"))
                .discountAmount(BigDecimal.ZERO)
                .status(0)
                .statusDesc("待付款")
                .remark("测试订单")
                .addressSnapshot("{\"name\":\"测试\"}")
                .createdAt(LocalDateTime.now())
                .items(Collections.singletonList(item))
                .build();
    }

    private Payment buildPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(ORDER_ID);
        payment.setUserId(USER_ID);
        payment.setPaymentNo("MOCK_PAY_1234567890");
        payment.setAmount(new BigDecimal("198.00"));
        payment.setPayType(1);
        payment.setStatus(1);
        payment.setPaidAt(LocalDateTime.now());
        return payment;
    }
}
