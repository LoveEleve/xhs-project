package com.myxhs.payment.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.request.RefundRequest;
import com.myxhs.payment.dto.response.PaymentVO;
import com.myxhs.payment.service.PaymentService;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 支付控制器
 * <p>
 * RESTful API 设计：
 * POST /api/payment/pay — 发起支付
 * POST /api/payment/callback/{payType} — 第三方支付回调
 * POST /api/payment/refund — 发起退款
 * POST /api/payment/refund-callback/{payType} — 第三方退款回调
 * GET  /api/payment/status/{orderId} — 查询支付状态
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class PaymentController {

    private final PaymentService paymentService;
    private final AccessTokenGuard accessTokenGuard;

    @PostConstruct
    public void validateTokens() {
        accessTokenGuard.requireInternalTokenConfigured("payment");
        accessTokenGuard.requireAdminTokenConfigured("payment");
    }

    /**
     * 发起支付
     */
    @PostMapping("/pay")
    @RateLimit(prefix = "myxhs:payment:pay", maxRequests = 10, windowSeconds = 60, perUser = true,
               message = "支付频率过高，请稍后再试")
    public R<PaymentVO> pay(@Valid @RequestBody PayCreateRequest request,
                            @RequestHeader("X-User-Id") Long userId,
                            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        // 仅允许内部调用（order服务 Feign）或有 admin 权限的调用
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[支付] 非内部调用被拒绝: userId={}, orderId={}", userId, request.getOrderId());
            return R.fail(403, "支付请通过订单服务发起");
        }
        request.setUserId(userId);
        return paymentService.pay(request);
    }

    /**
     * 第三方支付回调（支付宝/微信服务器 POST 通知）
     * <p>
     * Mock 模式下不会被调用。
     * 支付宝/微信模式下，第三方异步通知到此接口。
     * </p>
     *
     * @param payType 支付方式：1-支付宝 2-微信
     */
    @PostMapping("/callback/{payType}")
    public String payCallback(@PathVariable Integer payType,
                              @RequestBody String callbackData,
                              @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[支付回调] 非内部调用被拒绝: payType={}", payType);
            return "fail";
        }
        // 解析回调数据（不同支付渠道格式不同）
        // Mock 实现：简化解析逻辑
        String paymentNo = extractPaymentNo(callbackData, payType);
        if (paymentNo == null || paymentNo.isBlank()) {
            log.error("[支付回调] 缺少有效paymentNo，拒绝确认: payType={}", payType);
            return "fail";
        }
        String tradeNo = extractTradeNo(callbackData, payType);
        if (tradeNo == null || tradeNo.isBlank()) {
            log.error("[支付回调] 缺少有效tradeNo，拒绝确认: paymentNo={}", paymentNo);
            return "fail";
        }
        boolean success = extractPayResult(callbackData, payType);
        // P2-5: 回调体脱敏，仅记录摘要（原实现打印完整回调体，泄露支付数据）
        log.info("[支付回调] 收到回调: payType={}, paymentNo={}, success={}, bodySize={}B", payType, paymentNo, success, callbackData.length());

        paymentService.handlePayCallback(paymentNo, tradeNo, success);

        // 返回 success 给第三方（支付宝要求返回 "success"，微信要求返回 XML/JSON）
        return "success";
    }

    /**
     * 发起退款
     * <p>
     * T-061（2026-08-14）：补 X-Internal-Call 校验——原实现仅 X-User-Id，
     * 任何已登录用户可对任意订单退款（越权）。退款为资金敏感操作，与 pay 一致要求内部调用。
     * </p>
     */
    @PostMapping("/refund")
    @RateLimit(prefix = "myxhs:payment:refund", maxRequests = 5, windowSeconds = 60, perUser = true,
               message = "退款频率过高，请稍后再试")
    public R<Void> refund(@Valid @RequestBody RefundRequest request,
                          @RequestHeader("X-User-Id") Long userId,
                          @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[退款] 非内部调用被拒绝: userId={}, paymentId={}", userId, request.getPaymentId());
            return R.fail(403, "退款请通过订单服务发起");
        }
        request.setUserId(userId);
        return paymentService.refund(request);
    }

    /**
     * 第三方退款回调
     */
    @PostMapping("/refund-callback/{payType}")
    public String refundCallback(@PathVariable Integer payType,
                                 @RequestBody String callbackData,
                                 @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[退款回调] 非内部调用被拒绝: payType={}", payType);
            return "fail";
        }
        String refundNo = extractRefundNo(callbackData, payType);
        boolean success = extractRefundResult(callbackData, payType);
        // P2-5: 回调体脱敏
        log.info("[退款回调] 收到回调: payType={}, refundNo={}, success={}, bodySize={}B", payType, refundNo, success, callbackData.length());

        paymentService.handleRefundCallback(refundNo, success);

        return "success";
    }

    /**
     * 查询支付状态
     * <p>
     * T-061（2026-08-14）：补 X-Internal-Call 校验——原实现无鉴权，任意可查任意订单支付状态；
     * 用户面走 /api/order/pay/status/{orderId}（有归属校验），此端点仅 order 服务 Feign 内部调用。
     * </p>
     */
    @GetMapping("/status/{orderId}")
    public R<PaymentVO> getPaymentStatus(@PathVariable Long orderId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[支付状态] 非内部调用被拒绝: orderId={}", orderId);
            return R.fail(403, "仅限内部服务调用");
        }
        return paymentService.getPaymentStatus(orderId);
    }

    // ==================== 回调解析辅助方法 ====================

    private String extractPaymentNo(String callbackData, Integer payType) {
        // 支付宝格式：out_trade_no 字段
        // 微信格式：out_trade_no 字段
        // Mock 模式：尝试从 JSON body 中提取 out_trade_no
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            if (node.has("out_trade_no")) return node.get("out_trade_no").asText();
            if (node.has("payment_no")) return node.get("payment_no").asText();
        } catch (Exception e) {
            log.warn("[支付回调] 解析回调数据失败: {}", e.getMessage());
        }
        return null;
    }

    private String extractTradeNo(String callbackData, Integer payType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            if (node.has("trade_no")) return node.get("trade_no").asText();
            if (node.has("transaction_id")) return node.get("transaction_id").asText();
        } catch (Exception e) {
            log.warn("[支付回调] 交易号解析失败: {}", e.getMessage());
        }
        return null;
    }

    private boolean extractPayResult(String callbackData, Integer payType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            String tradeStatus = node.has("trade_status") ? node.get("trade_status").asText() : null;
            String resultCode = node.has("result_code") ? node.get("result_code").asText() : null;
            String status = node.has("status") ? node.get("status").asText() : null;
            return "TRADE_SUCCESS".equals(tradeStatus)
                    || "SUCCESS".equals(resultCode)
                    || "SUCCESS".equals(status);
        } catch (Exception e) {
            log.warn("[支付回调] 支付结果解析失败: {}", e.getMessage());
            return false;
        }
    }

    private String extractRefundNo(String callbackData, Integer payType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            if (node.has("refund_no")) return node.get("refund_no").asText();
            if (node.has("out_refund_no")) return node.get("out_refund_no").asText();
        } catch (Exception e) {
            log.warn("[退款回调] 解析回调数据失败: {}", e.getMessage());
        }
        return "REFUND_" + System.currentTimeMillis();
    }

    private boolean extractRefundResult(String callbackData, Integer payType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            String refundStatus = node.has("refund_status") ? node.get("refund_status").asText() : null;
            String resultCode = node.has("result_code") ? node.get("result_code").asText() : null;
            String status = node.has("status") ? node.get("status").asText() : null;
            return "REFUND_SUCCESS".equals(refundStatus)
                    || "SUCCESS".equals(resultCode)
                    || "SUCCESS".equals(status);
        } catch (Exception e) {
            log.warn("[退款回调] 退款结果解析失败: {}", e.getMessage());
            return false;
        }
    }
}
