package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.alipay.RefundTradeResult;
import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Mock 支付策略（测试/开发用同步渠道）
 * <p>
 * 与真实渠道的差异（有意为之的测试夹具）：
 * <ul>
 *   <li>pay：同步成功（无渠道交易、无异步通知）</li>
 *   <li>refund：同步成功（无渠道出金）</li>
 *   <li>queryPayStatus：返回 0（无外部交易——本地 pending 即"渠道未付"，可安全处置）</li>
 *   <li>close：无真实渠道交易，仅记录</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPayStrategy implements PayChannelStrategy {

    @Override
    public String pay(Long orderId, BigDecimal amount, String paymentNo) {
        log.info("[Mock支付] 发起支付: orderId={}, amount={}, paymentNo={}", orderId, amount, paymentNo);
        // Mock：直接返回 paymentNo 作为交易号，调用方会立即标记支付成功
        return paymentNo;
    }

    @Override
    public String buildPayParams(Long orderId, BigDecimal amount, String paymentNo) {
        // C-016：真实客户端用 orderStr 拉起 SDK；Mock 渠道无 SDK——给可解析的等价 JSON 载荷
        return "{\"channel\":\"MOCK\",\"paymentNo\":\"" + paymentNo + "\"}";
    }

    @Override
    public RefundTradeResult refund(String paymentNo, String outRequestNo, BigDecimal refundAmount, String reason) {
        log.info("[Mock退款] 同步退款成功: paymentNo={}, outRequestNo={}, amount={}, reason={}",
                paymentNo, outRequestNo, refundAmount, reason);
        // Mock：无渠道出金，直接成功（tradeNo 位用 outRequestNo 占位，与旧行为一致）
        return RefundTradeResult.success(outRequestNo);
    }

    @Override
    public Integer queryPayStatus(String paymentNo) {
        // Mock 无外部渠道交易：本地 pending 即渠道未付（0）——超时处置可安全置失败、换渠道可安全关闭
        log.debug("[Mock支付] 查询支付状态(无外部交易=未付): paymentNo={}", paymentNo);
        return 0;
    }

    @Override
    public void close(String paymentNo) {
        // Mock：无真实渠道交易；关闭 = 仅记录（真实渠道：alipay.trade.close）
        log.info("[Mock支付] 关闭渠道交易(模拟): paymentNo={}", paymentNo);
    }
}
