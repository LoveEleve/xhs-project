package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 微信支付 Mock 策略
 * <p>
 * 模拟微信支付异步回调流程：
 * 1. pay() → 模拟向微信支付提交预支付请求，返回模拟交易号
 * 2. 异步回调 → 由 PayCallbackController 接收（微信服务器 POST 通知）
 * 3. handleCallback() → 处理回调，更新支付状态
 * </p>
 * <p>
 * 与支付宝策略的区别：
 * - 微信支付先获取预支付交易会话标识（prepay_id），再由前端调起支付
 * - 微信支付回调通知格式与支付宝不同（XML vs JSON）
 * - 但在 Mock 模式下，这些差异不影响核心流程
 * </p>
 * <p>
 * 异步回调模拟机制：
 * 与支付宝类似，通过 PayCallbackSimulator 定时任务延迟发送回调通知。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayStrategy implements PayChannelStrategy {

    /** 微信支付模拟交易号前缀 */
    private static final String WECHAT_TRADE_NO_PREFIX = "WECHAT_MOCK_";

    @Override
    public String pay(Long orderId, java.math.BigDecimal amount, String paymentNo) {
        String tradeNo = WECHAT_TRADE_NO_PREFIX + System.currentTimeMillis();
        log.info("[微信Mock] 发起支付: orderId={}, amount={}, paymentNo={}, tradeNo={}",
                orderId, amount, paymentNo, tradeNo);
        // 模拟：返回交易号，等待异步回调
        // 实际场景中，这里会调用微信支付 SDK 的 unifiedorder 接口
        return tradeNo;
    }

    @Override
    public void handleCallback(String paymentNo, String tradeNo, boolean success) {
        log.info("[微信Mock] 处理回调: paymentNo={}, tradeNo={}, success={}", paymentNo, tradeNo, success);
        // 回调处理逻辑由 PaymentService 统一处理
    }

    @Override
    public String refund(Long paymentId, String refundNo, java.math.BigDecimal refundAmount, String reason) {
        String refundTradeNo = "WECHAT_REFUND_" + System.currentTimeMillis();
        log.info("[微信Mock] 发起退款: paymentId={}, refundNo={}, amount={}, reason={}, refundTradeNo={}",
                paymentId, refundNo, refundAmount, reason, refundTradeNo);
        // 模拟：返回退款交易号，等待异步回调
        return refundTradeNo;
    }

    @Override
    public void handleRefundCallback(String refundNo, boolean success) {
        log.info("[微信Mock] 处理退款回调: refundNo={}, success={}", refundNo, success);
        // 退款回调处理逻辑由 PaymentService 统一处理
    }

    @Override
    public Integer queryPayStatus(String paymentNo) {
        log.info("[微信Mock] 查询支付状态: paymentNo={}", paymentNo);
        // 模拟：返回1（支付成功）
        // 实际场景中，这里会调用微信支付 SDK 的 orderquery 接口
        return 1;
    }
}
