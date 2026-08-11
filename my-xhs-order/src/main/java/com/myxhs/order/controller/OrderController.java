package com.myxhs.order.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.order.dto.request.DeliverRequest;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.dto.request.PayRequest;
import com.myxhs.order.dto.response.OrderVO;
import com.myxhs.order.entity.Payment;
import com.myxhs.order.feign.PaymentFeignClient;
import com.myxhs.order.service.MockPayService;
import com.myxhs.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单接口
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class OrderController {

    private final OrderService orderService;
    /**
     * MockPayService 可选注入：仅在 pay.type=mock（默认）时存在。
     * 用 ObjectProvider 避免 pay.type=remote 时强依赖导致启动失败。
     */
    private final ObjectProvider<MockPayService> mockPayServiceProvider;
    private final PaymentFeignClient paymentFeignClient;

    /** 支付模式：mock=本地Mock支付，remote=调用独立支付服务 */
    @Value("${pay.type:mock}")
    private String payType;

    /**
     * 创建订单
     */
    @PostMapping("/create")
    @RateLimit(prefix = "myxhs:order:create", maxRequests = 5, windowSeconds = 60, perUser = true,
               message = "下单频率过高，请稍后再试")
    public R<OrderVO> createOrder(@RequestHeader("X-User-Id") Long userId,
                                  @Valid @RequestBody OrderCreateRequest request) {
        return R.ok(orderService.createOrder(userId, request));
    }

    /**
     * 订单详情
     */
    @GetMapping("/{orderId}")
    public R<OrderVO> getOrderDetail(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long orderId) {
        return R.ok(orderService.getOrderDetail(userId, orderId));
    }

    /**
     * 我的订单列表
     */
    @GetMapping("/list")
    public R<List<OrderVO>> getUserOrders(@RequestHeader("X-User-Id") Long userId,
                                          @RequestParam(required = false) Integer status) {
        return R.ok(orderService.getUserOrders(userId, status));
    }

    /**
     * 通过订单号查询订单（非分片键查询，走映射表路由）
     */
    @GetMapping("/by-order-no/{orderNo}")
    public R<OrderVO> getOrderByOrderNo(@PathVariable String orderNo,
            @RequestHeader("X-User-Id") Long userId) {
        return R.ok(orderService.getOrderByOrderNo(userId, orderNo));
    }

    /**
     * 取消订单
     */
    @PostMapping("/cancel")
    @RateLimit(prefix = "myxhs:order:cancel", maxRequests = 10, windowSeconds = 60, perUser = true)
    public R<Void> cancelOrder(@RequestHeader("X-User-Id") Long userId,
                               @RequestParam Long orderId) {
        orderService.cancelOrder(userId, orderId);
        return R.ok();
    }

    /**
     * 确认收货
     */
    @PostMapping("/confirm")
    public R<Void> confirmReceive(@RequestHeader("X-User-Id") Long userId,
                                  @RequestParam Long orderId) {
        orderService.confirmReceive(userId, orderId);
        return R.ok();
    }

    /**
     * 发货（状态流转：1-已付款 → 2-已发货）
     */
    @PostMapping("/deliver")
    @RateLimit(prefix = "myxhs:order:deliver", maxRequests = 10, windowSeconds = 60, perUser = true)
    public R<Void> deliverOrder(@RequestHeader("X-User-Id") Long userId,
                                @Valid @RequestBody DeliverRequest request) {
        orderService.deliverOrder(userId, request.getOrderId(),
                request.getLogisticsCompany(), request.getTrackingNo());
        return R.ok();
    }

    // ==================== 支付接口 ====================

    /**
     * 创建支付
     * <p>
     * 支持两种模式：
     * - mock（默认）：本地 MockPayService 直接支付成功
     * - remote：调用独立支付服务（my-xhs-payment）
     * </p>
     */
    @PostMapping("/pay/create")
    public R<Object> createPayment(@RequestHeader("X-User-Id") Long userId,
                                   @Valid @RequestBody PayRequest request) {
        if ("remote".equals(payType)) {
            OrderVO order = orderService.getOrderDetail(userId, request.getOrderId());
            PaymentFeignClient.PayCreateRequest payRequest = new PaymentFeignClient.PayCreateRequest();
            payRequest.setOrderId(request.getOrderId());
            payRequest.setUserId(userId);
            payRequest.setPayType(request.getPayType());
            payRequest.setAmount(order.getPayAmount());
            return paymentFeignClient.pay(payRequest, userId);
        }
        // Mock 模式：可能成功或失败
        MockPayService mockPayService = mockPayServiceProvider.getIfAvailable();
        if (mockPayService == null) {
            return R.fail(500, "MockPayService 未加载（pay.type=" + payType + "），无法处理 mock 模式请求");
        }
        MockPayService.PaymentResult result = mockPayService.createPayment(userId, request);
        if (!result.success()) {
            // 模拟支付失败 → 自动取消订单释放库存
            log.info("[订单] Mock支付失败，自动取消订单: orderId={}, reason={}",
                    result.orderId(), result.failReason());
            orderService.onPaymentFailed(result.orderId());
            return R.fail(400, "支付失败: " + result.failReason());
        }
        return R.ok(result.payment());
    }

    /**
     * 查询支付状态
     */
    @GetMapping("/pay/status/{orderId}")
    public R<Object> getPaymentStatus(@PathVariable Long orderId,
            @RequestHeader("X-User-Id") Long userId) {
        // 校验订单归属
        if (!orderService.isOrderOwner(userId, orderId)) {
            return R.fail(403, "无权查看该订单");
        }
        if ("remote".equals(payType)) {
            return paymentFeignClient.getPaymentStatus(orderId);
        }
        MockPayService mockPayService = mockPayServiceProvider.getIfAvailable();
        if (mockPayService == null) {
            return R.fail(500, "MockPayService 未加载（pay.type=" + payType + "），无法处理 mock 模式请求");
        }
        return R.ok(mockPayService.getPaymentByOrderId(orderId));
    }

    // ==================== 支付服务回调接口 ====================

    /** 内部服务调用令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.internal.token}")
    private String internalToken;

    private boolean isInternalCall(String headerValue) {
        return internalToken != null && !internalToken.isEmpty() && internalToken.equals(headerValue);
    }

    /**
     * 支付成功回调（仅允许内部支付服务调用）
     */
    @PostMapping("/pay-success")
    public R<Void> notifyPaySuccess(@RequestParam("orderId") Long orderId,
                                    @RequestParam("tradeNo") String tradeNo,
                                    @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            log.warn("[订单回调] 非内部调用被拒绝: pay-success, orderId={}", orderId);
            return R.fail(403, "仅允许内部服务调用");
        }
        log.info("[订单回调] 收到支付成功通知: orderId={}, tradeNo={}", orderId, tradeNo);
        boolean success = orderService.onPaymentSuccess(orderId, null);
        if (!success) {
            // P1-1：订单已非待付款（竞态取消/关单/已支付）→ 返回业务失败，供支付侧触发自动退款
            log.warn("[订单回调] 支付成功但订单状态不允许更新(已取消/已支付), orderId={}", orderId);
            return R.fail(ResultCode.ORDER_STATUS_ERROR, "订单状态不允许支付（已取消/已支付）");
        }
        return R.ok();
    }

    /**
     * 支付失败回调
     * <p>
     * 支付失败后自动取消订单，释放库存和优惠券。
     * 用户在"我的订单"中看到状态为"已取消"，可以重新下单。
     * </p>
     */
    @PostMapping("/pay-fail")
    public R<Void> notifyPayFail(@RequestParam("orderId") Long orderId,
                                  @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            log.warn("[订单回调] 非内部调用被拒绝: pay-fail, orderId={}", orderId);
            return R.fail(403, "仅允许内部服务调用");
        }
        log.info("[订单回调] 收到支付失败通知, 自动取消订单: orderId={}", orderId);
        try {
            // 自动取消订单 → 释放库存 + 退还优惠券
            orderService.onPaymentFailed(orderId);
        } catch (Exception e) {
            log.error("[订单回调] 支付失败自动取消失败: orderId={}", orderId, e);
        }
        return R.ok();
    }

    /**
     * 退款成功回调（由独立支付服务通过 Feign 调用）
     * <p>
     * 支付服务在退款成功后通知订单服务：
     * 1. 释放库存
     * 2. 退还优惠券
     * 3. 更新订单状态为"已退款"
     * </p>
     */
    @PostMapping("/refund-success")
    public R<Void> notifyRefundSuccess(@RequestParam("orderId") Long orderId,
                                       @RequestParam("refundNo") String refundNo,
                                       @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            log.warn("[订单回调] 非内部调用被拒绝: refund-success, orderId={}", orderId);
            return R.fail(403, "仅允许内部服务调用");
        }
        log.info("[订单回调] 收到退款成功通知: orderId={}, refundNo={}", orderId, refundNo);
        orderService.onRefundSuccess(orderId);
        return R.ok();
    }

    /**
     * 退款失败/关闭回调
     */
    @PostMapping("/refund-fail")
    public R<Void> notifyRefundFail(@RequestParam("orderId") Long orderId,
                                    @RequestParam("refundNo") String refundNo,
                                    @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            log.warn("[订单回调] 非内部调用被拒绝: refund-fail, orderId={}", orderId);
            return R.fail(403, "仅允许内部服务调用");
        }
        log.info("[订单回调] 收到退款失败通知: orderId={}, refundNo={}", orderId, refundNo);
        return R.ok();
    }

    /**
     * 查询订单支付金额（供支付服务校验使用，需 X-Internal-Call）
     */
    @GetMapping("/pay-amount")
    public R<BigDecimal> getOrderPayAmount(
            @RequestParam("orderId") Long orderId,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        BigDecimal payAmount = orderService.getOrderPayAmount(orderId);
        return R.ok(payAmount);
    }

    /**
     * 查询订单状态（P1-1：支付前回查订单是否待付款，需 X-Internal-Call）
     */
    @GetMapping("/status")
    public R<Integer> getOrderStatus(
            @RequestParam("orderId") Long orderId,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        return R.ok(orderService.getOrderStatus(orderId));
    }
}
