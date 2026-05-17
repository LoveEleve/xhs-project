package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 支付策略
 * <p>
 * 默认支付方式，直接在 pay() 方法中返回成功，
 * 不走第三方支付平台，用于开发和测试环境。
 * </p>
 * <p>
 * Mock 模式特点：
 * 1. pay() 是同步的，直接标记支付成功
 * 2. 不需要处理异步回调
 * 3. refund() 也是同步的，直接标记退款成功
 * 4. queryPayStatus() 直接查数据库
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPayStrategy implements PayChannelStrategy {

    @Override
    public String pay(Long orderId, java.math.BigDecimal amount, String paymentNo) {
        log.info("[Mock支付] 发起支付: orderId={}, amount={}, paymentNo={}", orderId, amount, paymentNo);
        // Mock：直接返回 paymentNo 作为交易号，调用方会立即标记支付成功
        return paymentNo;
    }

    @Override
    public void handleCallback(String paymentNo, String tradeNo, boolean success) {
        // Mock 模式不需要处理异步回调
        log.debug("[Mock支付] 忽略回调: paymentNo={}", paymentNo);
    }

    @Override
    public String refund(Long paymentId, String refundNo, java.math.BigDecimal refundAmount, String reason) {
        log.info("[Mock退款] 发起退款: paymentId={}, refundNo={}, amount={}, reason={}",
                paymentId, refundNo, refundAmount, reason);
        // Mock：直接返回 refundNo，调用方会立即标记退款成功
        return refundNo;
    }

    @Override
    public void handleRefundCallback(String refundNo, boolean success) {
        // Mock 模式不需要处理退款异步回调
        log.debug("[Mock退款] 忽略退款回调: refundNo={}", refundNo);
    }

    @Override
    public Integer queryPayStatus(String paymentNo) {
        // Mock：不需要查第三方，调用方会自行查数据库
        log.debug("[Mock支付] 查询支付状态: paymentNo={}", paymentNo);
        return null;
    }
}
