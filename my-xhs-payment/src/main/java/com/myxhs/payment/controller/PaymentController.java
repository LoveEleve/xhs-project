package com.myxhs.payment.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.request.RefundByOrderRequest;
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
        // 三态解析：UNKNOWN（格式不符/解析失败）必须"返回 fail 让渠道重试"，
        // 原实现按 false 处理 → 格式不符的成功回调会被标记支付失败并触发订单取消（资损/客诉）
        int parsed = parsePayResult(callbackData, payType);
        if (parsed < 0) {
            log.error("[支付回调] 回调结果无法解析(要求渠道重试, 不改支付状态): paymentNo={}, payType={}", paymentNo, payType);
            return "fail";
        }
        boolean success = parsed == 1;
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
     * 按订单维度发起退款（售后场景，内部调用）
     * <p>
     * 售后单只持有 orderId；由支付域解析 orderId → 支付单并复用 {@code /refund} 的
     * 全套校验（归属、可退金额、锁、幂等标记），上游无需感知 paymentId。
     * </p>
     */
    @PostMapping("/refund-by-order")
    @RateLimit(prefix = "myxhs:payment:refund-by-order", maxRequests = 5, windowSeconds = 60, perUser = true,
               message = "退款频率过高，请稍后再试")
    public R<String> refundByOrder(@Valid @RequestBody RefundByOrderRequest request,
                                 @RequestHeader("X-User-Id") Long userId,
                                 @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[退款] 非内部调用按订单退款被拒绝: userId={}, orderId={}", userId, request.getOrderId());
            return R.fail(403, "退款请通过订单/售后服务发起");
        }
        return paymentService.refundByOrder(request, userId);
    }

    /**
     * 按订单查询退款单（内部）：供售后恢复流程核对"退款是否已实际成功"
     */
    @GetMapping("/refunds/{orderId}")
    public R<java.util.List<com.myxhs.payment.dto.response.RefundVO>> listRefunds(
            @PathVariable("orderId") Long orderId,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return paymentService.listRefundsByOrder(orderId);
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
        if (refundNo == null) {
            log.error("[退款回调] 无法解析退款单号(要求渠道重试, 不处理): payType={}", payType);
            return "fail";
        }
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

    /**
     * 解析支付结果三态：1=成功 0=失败 -1=无法解析（未知，必须让渠道重试而不是落失败态）
     */
    private int parsePayResult(String callbackData, Integer payType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callbackData);
            String tradeStatus = node.has("trade_status") ? node.get("trade_status").asText() : null;
            String resultCode = node.has("result_code") ? node.get("result_code").asText() : null;
            String status = node.has("status") ? node.get("status").asText() : null;
            if ("TRADE_SUCCESS".equals(tradeStatus) || "SUCCESS".equals(resultCode) || "SUCCESS".equals(status)) {
                return 1;
            }
            // 渠道中间态（等待买家付款/用户支付中）不是失败：返回 -1 保持待支付并让渠道继续重试，
            // 否则 WAIT_BUYER_PAY/NOTPAY/USERPAYING 会被当成"支付失败"提前终结订单支付态
            if ("WAIT_BUYER_PAY".equals(tradeStatus) || "TRADE_WAIT_BUYER_PAY".equals(tradeStatus)
                    || "NOTPAY".equals(status) || "USERPAYING".equals(status)) {
                return -1;
            }
            // 明确的失败态才返回 0；三个字段都没有 → 无法判定
            if (tradeStatus != null || resultCode != null || status != null) {
                return 0;
            }
            return -1;
        } catch (Exception e) {
            log.warn("[支付回调] 支付结果解析失败: {}", e.getMessage());
            return -1;
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
        // 不得伪造单号：伪造会让 handleRefundCallback 查不到退款单而静默丢弃真实回调
        return null;
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
