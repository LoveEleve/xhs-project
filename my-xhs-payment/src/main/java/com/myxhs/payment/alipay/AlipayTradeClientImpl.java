package com.myxhs.payment.alipay;

import com.myxhs.payment.config.AlipayMockProperties;
import com.myxhs.payment.simulator.SimulatedAlipayServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝交易客户端实现（契约层 · C-003）
 * <p>
 * 商户侧 SDK 形态：构造请求参数 + <b>应用私钥签名</b> → 调用"模拟支付宝服务器"（进程内）。
 * 通知方向验签使用<b>支付宝公钥</b>（模拟服务器签名）。
 * </p>
 * <p>
 * 接入真实支付宝时：本类替换为官方 SDK 封装（alipay.trade.create/query/close/refund），
 * 其余链路（策略/服务/端点）零改动——这正是契约层隔离的意义。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayTradeClientImpl implements AlipayTradeClient {

    private final SimulatedAlipayServer simulatedAlipayServer;
    private final AlipayMockProperties properties;

    @Override
    public String tradeCreate(Long orderId, BigDecimal amount, String subject, String outTradeNo) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("method", "alipay.trade.create");
        request.put("out_trade_no", outTradeNo);
        request.put("total_amount", amount.toPlainString());
        request.put("subject", subject == null || subject.isBlank() ? "订单支付" : subject);
        request.put("order_id", String.valueOf(orderId));
        request.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return simulatedAlipayServer.handleCreateTrade(signRequest(request));
    }

    @Override
    public AlipayTradeQueryResult tradeQuery(String outTradeNo) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("method", "alipay.trade.query");
        request.put("out_trade_no", outTradeNo);
        request.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return simulatedAlipayServer.handleQueryTrade(signRequest(request));
    }

    @Override
    public boolean tradeClose(String outTradeNo) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("method", "alipay.trade.close");
        request.put("out_trade_no", outTradeNo);
        request.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return simulatedAlipayServer.handleCloseTrade(signRequest(request));
    }

    @Override
    public RefundTradeResult tradeRefund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("method", "alipay.trade.refund");
        request.put("out_trade_no", outTradeNo);
        request.put("out_request_no", outRequestNo);
        request.put("refund_amount", amount.toPlainString());
        request.put("refund_reason", reason == null ? "" : reason);
        request.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return simulatedAlipayServer.handleRefundTrade(signRequest(request));
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        return AlipaySignatureUtil.verify(params, properties.getAlipayPublicKey());
    }

    /** 商户请求签名（应用私钥；与线上"商户用应用私钥签请求"一致） */
    private Map<String, String> signRequest(Map<String, String> request) {
        request.put("sign", AlipaySignatureUtil.sign(request, properties.getMerchantPrivateKey()));
        return request;
    }
}
