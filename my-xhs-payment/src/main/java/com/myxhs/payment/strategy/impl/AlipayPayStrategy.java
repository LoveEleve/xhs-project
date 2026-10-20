package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.alipay.AlipayTradeClient;
import com.myxhs.payment.alipay.AlipayTradeQueryResult;
import com.myxhs.payment.alipay.RefundTradeResult;
import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 支付宝支付策略（渠道契约对齐 · C-003）
 * <p>
 * 本策略是<b>契约层之上</b>的薄适配：全部渠道交互委托 {@link AlipayTradeClient}
 * （真实场景 = 官方 SDK 封装；本地 = 模拟支付宝服务器 + 真实签名验签）。
 * </p>
 * <p>
 * 状态映射（四态 → 三值契约）：
 * <ul>
 *   <li>WAIT_BUYER_PAY → 0（未付）</li>
 *   <li>TRADE_SUCCESS / TRADE_FINISHED → 1（已付）</li>
 *   <li>TRADE_CLOSED → 0（对"本地 pending"单 = 未付款超时关闭；全退后关闭不可能出现在 pending 单）</li>
 *   <li>不可达/不可判定 → null（"不知道"≠"没付"）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayPayStrategy implements PayChannelStrategy {

    private final AlipayTradeClient alipayTradeClient;

    @Override
    public String pay(Long orderId, BigDecimal amount, String paymentNo) {
        String tradeNo = alipayTradeClient.tradeCreate(orderId, amount, "订单支付", paymentNo);
        log.info("[支付宝] 发起支付: orderId={}, amount={}, paymentNo={}, tradeNo={}",
                orderId, amount, paymentNo, tradeNo);
        return tradeNo;
    }

    @Override
    public String buildPayParams(Long orderId, BigDecimal amount, String paymentNo) {
        // C-016：客户端拉起支付参数（alipay.trade.app.pay orderStr，本地应用私钥签名）
        return alipayTradeClient.buildAppPayOrderStr(orderId, amount, "订单支付", paymentNo);
    }

    @Override
    public RefundTradeResult refund(String paymentNo, String outRequestNo, BigDecimal refundAmount, String reason) {
        RefundTradeResult result = alipayTradeClient.tradeRefund(paymentNo, outRequestNo, refundAmount, reason);
        log.info("[支付宝] 同步退款: paymentNo={}, outRequestNo={}, fundChange={}, errorCode={}",
                paymentNo, outRequestNo, result.isFundChange(), result.getErrorCode());
        return result;
    }

    @Override
    public Integer queryPayStatus(String paymentNo) {
        AlipayTradeQueryResult result = alipayTradeClient.tradeQuery(paymentNo);
        if (result == null || result.getStatus() == null) {
            log.warn("[支付宝] 查单不可达/不可判定: paymentNo={}", paymentNo);
            return null;
        }
        Integer mapped = switch (result.getStatus()) {
            case TRADE_SUCCESS, TRADE_FINISHED -> 1;
            case WAIT_BUYER_PAY, TRADE_CLOSED -> 0;
        };
        log.info("[支付宝] 查单: paymentNo={}, tradeStatus={} → {}", paymentNo, result.getStatus(), mapped);
        return mapped;
    }

    @Override
    public void close(String paymentNo) {
        boolean closed = alipayTradeClient.tradeClose(paymentNo);
        if (!closed) {
            // 已支付交易不可关闭（官方 ACQ.TRADE_HAS_SUCCESS）→ 抛异常让调用方"先查后决"
            throw new IllegalStateException("渠道交易不可关闭（已支付或状态异常）: " + paymentNo);
        }
        log.info("[支付宝] 关闭渠道交易: paymentNo={}", paymentNo);
    }
}
