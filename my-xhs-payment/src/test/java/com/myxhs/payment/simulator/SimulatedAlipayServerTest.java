package com.myxhs.payment.simulator;

import com.myxhs.payment.alipay.AlipaySignatureUtil;
import com.myxhs.payment.alipay.AlipayTradeQueryResult;
import com.myxhs.payment.alipay.AlipayTradeStatus;
import com.myxhs.payment.alipay.RefundTradeResult;
import com.myxhs.payment.config.AlipayMockProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模拟支付宝服务器单测
 * <p>
 * 验证口径：mock 台账仓储与通知投递器（不依赖 Redis/网络），签名验签走<b>真实 RSA2 链路</b>——
 * 商户请求真签真验、通知参数真签真验（支付宝公钥可验）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulatedAlipayServerTest {

    private static final String APP_ID = "mock-app-2026";
    private static final String SELLER_ID = "mock-seller-2026";

    private static String merchantPrivateKey;
    private static String merchantPublicKey;
    private static String alipayPrivateKey;
    private static String alipayPublicKey;

    @Mock
    private MockAlipayTradeRepository repository;
    @Mock
    private AlipayNotifySender notifySender;

    private AlipayMockProperties properties;
    private SimulatedAlipayServer server;

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
        properties = new AlipayMockProperties();
        properties.setAppId(APP_ID);
        properties.setSellerId(SELLER_ID);
        properties.setMerchantPrivateKey(merchantPrivateKey);
        properties.setMerchantPublicKey(merchantPublicKey);
        properties.setAlipayPrivateKey(alipayPrivateKey);
        properties.setAlipayPublicKey(alipayPublicKey);
        properties.setCallbackDelayMinMs(0);
        properties.setCallbackDelayMaxMs(0);
        properties.setSuccessRate(1.0);
        properties.setRetryIntervals(List.of("5s", "15s", "60s"));
        properties.setRetryMaxAttempts(8);
        server = new SimulatedAlipayServer(repository, notifySender, properties);
    }

    /** 构造已签名的商户请求（应用私钥） */
    private Map<String, String> signedRequest(String method, String outTradeNo, String... kv) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("out_trade_no", outTradeNo);
        for (int i = 0; i < kv.length; i += 2) {
            request.put(kv[i], kv[i + 1]);
        }
        request.put("sign", AlipaySignatureUtil.sign(request, merchantPrivateKey));
        return request;
    }

    private MockTradeRecord record(String outTradeNo, String status, String amount) {
        MockTradeRecord record = new MockTradeRecord();
        record.setOutTradeNo(outTradeNo);
        record.setTradeNo("ALIPAY_TRADE_001");
        record.setOrderId(10001L);
        record.setAmount(amount);
        record.setRefundedAmount("0.00");
        record.setSubject("测试订单");
        record.setStatus(status);
        record.setCreatedAt(System.currentTimeMillis());
        return record;
    }

    // ==================== 请求方向 ====================

    @Test
    @DisplayName("trade.create：台账建立（WAIT_BUYER_PAY + 金额/标题/付款模拟时间）")
    void createTradeBuildsLedger() {
        when(repository.load("PAY1")).thenReturn(null);

        String tradeNo = server.handleCreateTrade(signedRequest("alipay.trade.create", "PAY1",
                "total_amount", "199.00", "order_id", "10001", "subject", "测试订单"));

        assertThat(tradeNo).startsWith("ALIPAY_TRADE_");
        ArgumentCaptor<MockTradeRecord> captor = ArgumentCaptor.forClass(MockTradeRecord.class);
        verify(repository).save(captor.capture());
        MockTradeRecord saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("WAIT_BUYER_PAY");
        assertThat(saved.getAmount()).isEqualTo("199.00");
        assertThat(saved.getOrderId()).isEqualTo(10001L);
        assertThat(saved.getRefundedAmount()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("trade.create：同单号幂等（返回既有交易，不重复建账）")
    void createTradeIdempotent() {
        when(repository.load("PAY1")).thenReturn(record("PAY1", "WAIT_BUYER_PAY", "199.00"));

        String tradeNo = server.handleCreateTrade(signedRequest("alipay.trade.create", "PAY1",
                "total_amount", "199.00", "order_id", "10001"));

        assertThat(tradeNo).isEqualTo("ALIPAY_TRADE_001");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("商户请求验签失败（篡改参数）→ 拒绝")
    void requestSignVerificationFails() {
        Map<String, String> request = signedRequest("alipay.trade.create", "PAY1",
                "total_amount", "199.00", "order_id", "10001");
        request.put("total_amount", "0.01"); // 篡改

        assertThatThrownBy(() -> server.handleCreateTrade(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("验签失败");
    }

    @Test
    @DisplayName("trade.query：返回台账状态；台账不存在 → null（不可判定）")
    void queryTradeReadsLedger() {
        when(repository.load("PAY1")).thenReturn(record("PAY1", "TRADE_SUCCESS", "199.00"));
        AlipayTradeQueryResult result = server.handleQueryTrade(signedRequest("alipay.trade.query", "PAY1"));
        assertThat(result.getStatus()).isEqualTo(AlipayTradeStatus.TRADE_SUCCESS);

        when(repository.load("PAY2")).thenReturn(null);
        assertThat(server.handleQueryTrade(signedRequest("alipay.trade.query", "PAY2"))).isNull();
    }

    @Test
    @DisplayName("trade.close：WAIT → 关闭 + 安排异步通知；已支付 → 拒绝（ACQ.TRADE_HAS_SUCCESS）")
    void closeTradeRules() {
        MockTradeRecord waiting = record("PAY1", "WAIT_BUYER_PAY", "199.00");
        when(repository.tryLock("PAY1")).thenReturn(true);
        when(repository.load("PAY1")).thenReturn(waiting);
        assertThat(server.handleCloseTrade(signedRequest("alipay.trade.close", "PAY1"))).isTrue();
        assertThat(waiting.getStatus()).isEqualTo("TRADE_CLOSED");
        assertThat(waiting.getNextRetryAt()).isGreaterThan(0); // 关单通知异步投递

        MockTradeRecord paid = record("PAY2", "TRADE_SUCCESS", "199.00");
        when(repository.tryLock("PAY2")).thenReturn(true);
        when(repository.load("PAY2")).thenReturn(paid);
        assertThat(server.handleCloseTrade(signedRequest("alipay.trade.close", "PAY2"))).isFalse();
        assertThat(paid.getStatus()).isEqualTo("TRADE_SUCCESS"); // 不可关闭
    }

    // ==================== 退款 ====================

    @Test
    @DisplayName("trade.refund：同步成功 + 累计 + 全退后交易关闭（官方语义）")
    void refundSuccessAndFullClose() {
        MockTradeRecord paid = record("PAY1", "TRADE_SUCCESS", "199.00");
        when(repository.load("PAY1")).thenReturn(paid);
        when(repository.loadRefundResult("REQ1")).thenReturn(null);

        RefundTradeResult result = server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY1",
                "out_request_no", "REQ1", "refund_amount", "199.00"));

        assertThat(result.isFundChange()).isTrue();
        assertThat(paid.getRefundedAmount()).isEqualTo("199.00");
        assertThat(paid.getStatus()).isEqualTo("TRADE_CLOSED");
        verify(repository).saveRefundResult(eq("REQ1"), any(RefundTradeResult.class));
    }

    @Test
    @DisplayName("trade.refund：同 out_request_no 幂等（只退一次，返回既有结果）")
    void refundIdempotentByOutRequestNo() {
        when(repository.loadRefundResult("REQ1")).thenReturn(RefundTradeResult.success("OLD_REFUND"));

        RefundTradeResult result = server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY1",
                "out_request_no", "REQ1", "refund_amount", "199.00"));

        assertThat(result.getTradeNo()).isEqualTo("OLD_REFUND");
        verify(repository, never()).load(anyString());
    }

    @Test
    @DisplayName("trade.refund：未支付/已关闭/超额 → 失败码")
    void refundFailCodes() {
        when(repository.loadRefundResult(anyString())).thenReturn(null);

        when(repository.load("PAY1")).thenReturn(record("PAY1", "WAIT_BUYER_PAY", "199.00"));
        assertThat(server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY1",
                "out_request_no", "R1", "refund_amount", "10.00")).getErrorCode())
                .isEqualTo("ACQ.TRADE_NOT_ALLOW_REFUND");

        when(repository.load("PAY2")).thenReturn(record("PAY2", "TRADE_CLOSED", "199.00"));
        assertThat(server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY2",
                "out_request_no", "R2", "refund_amount", "10.00")).getErrorCode())
                .isEqualTo("ACQ.TRADE_HAS_CLOSE");

        MockTradeRecord paid = record("PAY3", "TRADE_SUCCESS", "199.00");
        paid.setRefundedAmount("190.00");
        when(repository.load("PAY3")).thenReturn(paid);
        assertThat(server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY3",
                "out_request_no", "R3", "refund_amount", "10.00")).getErrorCode())
                .isEqualTo("ACQ.REFUND_AMT_NOT_EQUAL_TOTAL");
    }

    @Test
    @DisplayName("故障注入：退款调用异常（结果未知，不落幂等键）")
    void refundFaultInjection() {
        properties.setRefundFaultInjection(true);
        when(repository.loadRefundResult(anyString())).thenReturn(null);

        assertThatThrownBy(() -> server.handleRefundTrade(signedRequest("alipay.trade.refund", "PAY1",
                "out_request_no", "REQ1", "refund_amount", "10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("故障注入");
        verify(repository, never()).saveRefundResult(anyString(), any());
    }

    // ==================== 通知投递 ====================

    @Test
    @DisplayName("触发付款：TRADE_SUCCESS 通知（参数完整 + 支付宝签名可验 + ACK 标记）")
    void triggerPayDeliversSignedNotify() {
        MockTradeRecord waiting = record("PAY1", "WAIT_BUYER_PAY", "199.00");
        when(repository.tryLock("PAY1")).thenReturn(true);
        when(repository.load("PAY1")).thenReturn(waiting);
        when(notifySender.send(anyMap())).thenReturn(true);

        server.triggerPay("PAY1", true);

        assertThat(waiting.getStatus()).isEqualTo("TRADE_SUCCESS");
        assertThat(waiting.isNotifyDelivered()).isTrue();
        assertThat(waiting.getNotifyId()).startsWith("NID-PAY1-");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notifySender).send(captor.capture());
        Map<String, String> params = captor.getValue();
        assertThat(params.get("trade_status")).isEqualTo("TRADE_SUCCESS");
        assertThat(params.get("total_amount")).isEqualTo("199.00");
        assertThat(params.get("app_id")).isEqualTo(APP_ID);
        assertThat(params.get("seller_id")).isEqualTo(SELLER_ID);
        assertThat(params.get("notify_type")).isEqualTo("trade_status_sync");
        // 真实签名链路：通知签名可用支付宝公钥验签
        assertThat(AlipaySignatureUtil.verify(params, alipayPublicKey)).isTrue();
    }

    @Test
    @DisplayName("通知未 ACK → 安排重试（attempts+1，notify_id 不变）")
    void notifyRetryScheduledOnNoAck() {
        MockTradeRecord waiting = record("PAY1", "WAIT_BUYER_PAY", "199.00");
        when(repository.tryLock("PAY1")).thenReturn(true);
        when(repository.load("PAY1")).thenReturn(waiting);
        when(notifySender.send(anyMap())).thenReturn(false);

        server.triggerPay("PAY1", true);

        assertThat(waiting.isNotifyDelivered()).isFalse();
        assertThat(waiting.getNotifyAttempts()).isEqualTo(1);
        assertThat(waiting.getNextRetryAt()).isGreaterThan(System.currentTimeMillis()); // 5s 后重试
    }

    @Test
    @DisplayName("重试扫描：到期未送达 → 重投（notify_id 不变）；超上限 → 放弃（查单兜底）")
    void retryScanRedeliversAndGivesUp() {
        MockTradeRecord record = record("PAY1", "TRADE_SUCCESS", "199.00");
        record.setNotifyId("NID-PAY1-FIXED");
        record.setNotifyAttempts(1);
        record.setNextRetryAt(System.currentTimeMillis() - 1);
        when(repository.scanAll()).thenReturn(List.of(record));
        when(repository.tryLock("PAY1")).thenReturn(true);
        when(repository.load("PAY1")).thenReturn(record);
        when(notifySender.send(anyMap())).thenReturn(true);

        server.retryUndeliveredNotifies();

        assertThat(record.isNotifyDelivered()).isTrue();
        assertThat(record.getNotifyAttempts()).isEqualTo(2);
        assertThat(record.getNotifyId()).isEqualTo("NID-PAY1-FIXED"); // 重试不变

        // 超上限：放弃
        properties.setRetryMaxAttempts(1);
        MockTradeRecord exhausted = record("PAY2", "TRADE_SUCCESS", "199.00");
        exhausted.setNotifyId("NID-PAY2");
        exhausted.setNextRetryAt(System.currentTimeMillis() - 1);
        when(repository.scanAll()).thenReturn(List.of(exhausted));
        when(repository.tryLock("PAY2")).thenReturn(true);
        when(repository.load("PAY2")).thenReturn(exhausted);
        when(notifySender.send(anyMap())).thenReturn(false);

        server.retryUndeliveredNotifies();

        assertThat(exhausted.isNotifyDelivered()).isFalse();
        assertThat(exhausted.getNextRetryAt()).isEqualTo(0); // 放弃投递
    }

    @Test
    @DisplayName("自动付款扫描：掷骰成功 → 转成功并投递")
    void simulateUserPaymentSuccess() {
        MockTradeRecord record = record("PAY1", "WAIT_BUYER_PAY", "199.00");
        record.setPayAfterAt(System.currentTimeMillis() - 1);
        when(repository.scanAll()).thenReturn(List.of(record));
        when(repository.tryLock("PAY1")).thenReturn(true);
        when(repository.load("PAY1")).thenReturn(record);
        when(notifySender.send(anyMap())).thenReturn(true);

        server.simulateUserPayment();

        assertThat(record.getStatus()).isEqualTo("TRADE_SUCCESS");
        assertThat(record.isAutoPayRolled()).isTrue();
        verify(notifySender).send(anyMap());
    }

    @Test
    @DisplayName("自动付款扫描：掷骰未中 → 停留 WAIT（用户未完成，一次性不再掷骰，不发通知）")
    void simulateUserPaymentAbandoned() {
        properties.setSuccessRate(0.0);
        MockTradeRecord abandoned = record("PAY2", "WAIT_BUYER_PAY", "199.00");
        abandoned.setPayAfterAt(System.currentTimeMillis() - 1);
        when(repository.scanAll()).thenReturn(List.of(abandoned));
        when(repository.tryLock("PAY2")).thenReturn(true);
        when(repository.load("PAY2")).thenReturn(abandoned);

        server.simulateUserPayment();

        assertThat(abandoned.getStatus()).isEqualTo("WAIT_BUYER_PAY");
        assertThat(abandoned.isAutoPayRolled()).isTrue(); // 一次性：不再掷骰
        verify(notifySender, never()).send(anyMap()); // 未支付不发通知（由超时处置兜底）
    }
}
