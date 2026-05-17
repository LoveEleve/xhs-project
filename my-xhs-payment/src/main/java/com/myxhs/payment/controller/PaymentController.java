package com.myxhs.payment.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.request.RefundRequest;
import com.myxhs.payment.dto.response.PaymentVO;
import com.myxhs.payment.service.PaymentService;
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
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 发起支付
     */
    @PostMapping("/pay")
    public R<PaymentVO> pay(@Valid @RequestBody PayCreateRequest request,
                            @RequestHeader("X-User-Id") Long userId) {
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
                              @RequestBody String callbackData) {
        log.info("[支付回调] 收到回调: payType={}, data={}", payType, callbackData);
        // 解析回调数据（不同支付渠道格式不同）
        // Mock 实现：简化解析逻辑
        String paymentNo = extractPaymentNo(callbackData, payType);
        String tradeNo = extractTradeNo(callbackData, payType);
        boolean success = extractPayResult(callbackData, payType);

        paymentService.handlePayCallback(paymentNo, tradeNo, success);

        // 返回 success 给第三方（支付宝要求返回 "success"，微信要求返回 XML/JSON）
        return "success";
    }

    /**
     * 发起退款
     */
    @PostMapping("/refund")
    public R<Void> refund(@Valid @RequestBody RefundRequest request,
                          @RequestHeader("X-User-Id") Long userId) {
        request.setUserId(userId);
        return paymentService.refund(request);
    }

    /**
     * 第三方退款回调
     */
    @PostMapping("/refund-callback/{payType}")
    public String refundCallback(@PathVariable Integer payType,
                                 @RequestBody String callbackData) {
        log.info("[退款回调] 收到回调: payType={}, data={}", payType, callbackData);
        String refundNo = extractRefundNo(callbackData, payType);
        boolean success = extractRefundResult(callbackData, payType);

        paymentService.handleRefundCallback(refundNo, success);

        return "success";
    }

    /**
     * 查询支付状态
     */
    @GetMapping("/status/{orderId}")
    public R<PaymentVO> getPaymentStatus(@PathVariable Long orderId) {
        return paymentService.getPaymentStatus(orderId);
    }

    // ==================== 回调解析辅助方法 ====================

    private String extractPaymentNo(String callbackData, Integer payType) {
        // Mock 实现：从回调数据中提取 paymentNo
        // 支付宝格式：trade_no 字段
        // 微信格式：out_trade_no 字段
        return "PAY_" + System.currentTimeMillis();
    }

    private String extractTradeNo(String callbackData, Integer payType) {
        // Mock 实现：从回调数据中提取第三方交易号
        return "TRADE_" + System.currentTimeMillis();
    }

    private boolean extractPayResult(String callbackData, Integer payType) {
        // Mock 实现：从回调数据中提取支付结果
        // 支付宝：trade_status == "TRADE_SUCCESS"
        // 微信：result_code == "SUCCESS"
        return callbackData.contains("success") || callbackData.contains("SUCCESS");
    }

    private String extractRefundNo(String callbackData, Integer payType) {
        // Mock 实现：从退款回调数据中提取 refundNo
        return "REFUND_" + System.currentTimeMillis();
    }

    private boolean extractRefundResult(String callbackData, Integer payType) {
        // Mock 实现：从退款回调数据中提取退款结果
        return callbackData.contains("success") || callbackData.contains("SUCCESS") || callbackData.contains("REFUND_SUCCESS");
    }
}
