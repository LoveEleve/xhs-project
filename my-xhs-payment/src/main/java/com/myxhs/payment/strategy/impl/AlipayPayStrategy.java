package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付宝 Mock 策略
 * <p>
 * 模拟支付宝异步回调流程：
 * 1. pay() → 模拟向支付宝提交支付请求，返回模拟交易号
 * 2. 异步回调 → 由 PayCallbackSimulator 模拟延迟回调（真实渠道接入时替换为回调端点 + 验签）
 * 3. handleCallback() → 处理回调，更新支付状态
 * </p>
 * <p>
 * 为什么用 Mock 而不是真实支付宝 SDK？
 * - 真实支付宝 SDK 需要商户资质（AppID、私钥等）
 * - Mock 模式可以完整模拟异步回调流程，体现架构设计能力
 * - 只需替换为真实 SDK 即可上线
 * </p>
 * <p>
 * 异步回调模拟机制：
 * pay() 被调用后，通过定时任务（PayCallbackSimulator）延迟 1~3 秒发送回调通知，
 * 模拟第三方支付平台的异步通知行为。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayPayStrategy implements PayChannelStrategy {

    /** 支付宝模拟交易号前缀 */
    private static final String ALIPAY_TRADE_NO_PREFIX = "ALIPAY_MOCK_";

    /** Mock 渠道真值模拟器（ITER-002 阶段④：queryPayStatus 以"待回调标记"判定交易未决） */
    private final com.myxhs.payment.simulator.PayCallbackSimulator callbackSimulator;

    @Override
    public String pay(Long orderId, java.math.BigDecimal amount, String paymentNo) {
        String tradeNo = ALIPAY_TRADE_NO_PREFIX + System.currentTimeMillis();
        log.info("[支付宝Mock] 发起支付: orderId={}, amount={}, paymentNo={}, tradeNo={}",
                orderId, amount, paymentNo, tradeNo);
        // 模拟：返回交易号，等待异步回调
        // 实际场景中，这里会调用支付宝 SDK 的 alipay.trade.page.pay()
        return tradeNo;
    }

    @Override
    public void handleCallback(String paymentNo, String tradeNo, boolean success) {
        log.info("[支付宝Mock] 处理回调: paymentNo={}, tradeNo={}, success={}", paymentNo, tradeNo, success);
        // 回调处理逻辑由 PaymentService 统一处理
        // 此处只做日志记录，具体状态更新在 PaymentService.handlePayCallback() 中
    }

    @Override
    public String refund(Long paymentId, String refundNo, java.math.BigDecimal refundAmount, String reason) {
        String refundTradeNo = "ALIPAY_REFUND_" + System.currentTimeMillis();
        log.info("[支付宝Mock] 发起退款: paymentId={}, refundNo={}, amount={}, reason={}, refundTradeNo={}",
                paymentId, refundNo, refundAmount, reason, refundTradeNo);
        // 模拟：返回退款交易号，等待异步回调
        return refundTradeNo;
    }

    @Override
    public void handleRefundCallback(String refundNo, boolean success) {
        log.info("[支付宝Mock] 处理退款回调: refundNo={}, success={}", refundNo, success);
        // 退款回调处理逻辑由 PaymentService 统一处理
    }

    @Override
    public Integer queryPayStatus(String paymentNo) {
        log.info("[支付宝Mock] 查询支付状态: paymentNo={}", paymentNo);
        // ITER-002 阶段④：Mock 渠道真值 = 待回调标记（存在=未决→0；不存在=未知→null）
        // 真实场景中，这里会调用支付宝 SDK 的 alipay.trade.query()
        return callbackSimulator.querySimulatedChannelStatus(paymentNo);
    }

    @Override
    public void close(String paymentNo) {
        // Mock：关闭 = 取消待回调注册（不再发送模拟回调）+ 记录（真实渠道：alipay.trade.close）
        callbackSimulator.cancel(paymentNo);
        log.info("[支付宝Mock] 关闭渠道交易(模拟): paymentNo={}", paymentNo);
    }
}
