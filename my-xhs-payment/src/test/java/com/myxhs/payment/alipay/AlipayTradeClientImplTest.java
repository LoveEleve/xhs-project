package com.myxhs.payment.alipay;

import com.myxhs.payment.config.AlipayMockProperties;
import com.myxhs.payment.simulator.SimulatedAlipayServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付宝交易客户端实现单测
 * <p>
 * 验证口径：mock 模拟服务器（不重复服务器逻辑），重点验证<b>商户侧行为</b>——
 * 请求参数构造 + 应用私钥真实签名（模拟服务器可验）+ 通知验签走支付宝公钥。
 * </p>
 */
class AlipayTradeClientImplTest {

    private static String merchantPrivateKey;
    private static String merchantPublicKey;
    private static String alipayPrivateKey;
    private static String alipayPublicKey;

    private SimulatedAlipayServer simulatedAlipayServer;
    private AlipayTradeClientImpl client;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair merchant = generator.generateKeyPair();
        merchantPrivateKey = Base64.getEncoder().encodeToString(merchant.getPrivate().getEncoded());
        merchantPublicKey = Base64.getEncoder().encodeToString(merchant.getPublic().getEncoded());
        KeyPair alipay = generator.generateKeyPair();
        alipayPrivateKey = Base64.getEncoder().encodeToString(alipay.getPrivate().getEncoded());
        alipayPublicKey = Base64.getEncoder().encodeToString(alipay.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        simulatedAlipayServer = mock(SimulatedAlipayServer.class);
        AlipayMockProperties properties = new AlipayMockProperties();
        properties.setMerchantPrivateKey(merchantPrivateKey);
        properties.setMerchantPublicKey(merchantPublicKey);
        properties.setAlipayPrivateKey(alipayPrivateKey);
        properties.setAlipayPublicKey(alipayPublicKey);
        client = new AlipayTradeClientImpl(simulatedAlipayServer, properties);
    }

    @Test
    @DisplayName("tradeCreate：请求参数完整 + 应用私钥签名（服务器侧可验签）")
    void tradeCreateSignsRequest() {
        when(simulatedAlipayServer.handleCreateTrade(anyMap())).thenReturn("ALIPAY_TRADE_001");

        String tradeNo = client.tradeCreate(10001L, new BigDecimal("199.00"), "测试订单", "PAY1");

        assertThat(tradeNo).isEqualTo("ALIPAY_TRADE_001");
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(simulatedAlipayServer).handleCreateTrade(captor.capture());
        Map<String, String> request = captor.getValue();
        assertThat(request.get("method")).isEqualTo("alipay.trade.create");
        assertThat(request.get("out_trade_no")).isEqualTo("PAY1");
        assertThat(request.get("total_amount")).isEqualTo("199.00");
        assertThat(request.get("subject")).isEqualTo("测试订单");
        assertThat(request.get("order_id")).isEqualTo("10001");
        // 真实签名：应用公钥可验
        assertThat(AlipaySignatureUtil.verify(request, merchantPublicKey)).isTrue();
    }

    @Test
    @DisplayName("tradeQuery / tradeClose / tradeRefund：透传 + 签名")
    void queryCloseRefundPassThrough() {
        when(simulatedAlipayServer.handleQueryTrade(anyMap()))
                .thenReturn(new AlipayTradeQueryResult("T1", AlipayTradeStatus.TRADE_SUCCESS));
        when(simulatedAlipayServer.handleCloseTrade(anyMap())).thenReturn(true);
        when(simulatedAlipayServer.handleRefundTrade(anyMap()))
                .thenReturn(RefundTradeResult.success("R1"));

        assertThat(client.tradeQuery("PAY1").getStatus()).isEqualTo(AlipayTradeStatus.TRADE_SUCCESS);
        assertThat(client.tradeClose("PAY1")).isTrue();
        assertThat(client.tradeRefund("PAY1", "REQ1", new BigDecimal("10.00"), "测试退款").isFundChange()).isTrue();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(simulatedAlipayServer).handleRefundTrade(captor.capture());
        Map<String, String> refundRequest = captor.getValue();
        assertThat(refundRequest.get("out_request_no")).isEqualTo("REQ1");
        assertThat(refundRequest.get("refund_amount")).isEqualTo("10.00");
        assertThat(AlipaySignatureUtil.verify(refundRequest, merchantPublicKey)).isTrue();
    }

    @Test
    @DisplayName("verifyNotify：支付宝公钥真实验签（合法通过 / 篡改失败）")
    void verifyNotifyUsesAlipayPublicKey() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "PAY1");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "199.00");
        params.put("sign", AlipaySignatureUtil.sign(params, alipayPrivateKey));

        assertThat(client.verifyNotify(params)).isTrue();

        params.put("total_amount", "0.01");
        assertThat(client.verifyNotify(params)).isFalse();
    }
}
