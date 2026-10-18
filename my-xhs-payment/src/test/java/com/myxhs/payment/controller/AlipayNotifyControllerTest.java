package com.myxhs.payment.controller;

import com.myxhs.payment.alipay.AlipaySignatureUtil;
import com.myxhs.payment.alipay.AlipayTradeClient;
import com.myxhs.payment.config.AlipayMockProperties;
import com.myxhs.payment.entity.Payment;
import com.myxhs.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付宝通知端点单测（standalone MockMvc——不依赖 Spring 上下文/运行时）
 * <p>
 * 验签路径为<b>真实签名链路</b>：mock 客户端的 verifyNotify 委托真实 {@link AlipaySignatureUtil}
 * （支付宝私钥签、支付宝公钥验）——覆盖"签名 → 验签 → 二次校验 → 四态分流 → 明文应答"全流程。
 * </p>
 */
class AlipayNotifyControllerTest {

    private static final String APP_ID = "mock-app-2026";
    private static final String SELLER_ID = "mock-seller-2026";
    private static final String PAYMENT_NO = "PAY20261017001";
    private static final String TRADE_NO = "ALIPAY_TRADE_001";

    private static String alipayPrivateKeyB64;
    private static String alipayPublicKeyB64;

    private AlipayTradeClient alipayTradeClient;
    private PaymentService paymentService;
    private MockMvc mockMvc;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        alipayPrivateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        alipayPublicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        alipayTradeClient = mock(AlipayTradeClient.class);
        paymentService = mock(PaymentService.class);

        AlipayMockProperties properties = new AlipayMockProperties();
        properties.setAppId(APP_ID);
        properties.setSellerId(SELLER_ID);
        properties.setAlipayPublicKey(alipayPublicKeyB64);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AlipayNotifyController(alipayTradeClient, properties, paymentService))
                .build();

        // 真实验签委托：mock 客户端 → 真实 RSA2 验签工具（端点测试覆盖真实签名链路）
        when(alipayTradeClient.verifyNotify(anyMap())).thenAnswer(
                invocation -> AlipaySignatureUtil.verify(invocation.getArgument(0), alipayPublicKeyB64));
    }

    private Payment localPayment(String amount) {
        Payment payment = new Payment();
        payment.setPaymentNo(PAYMENT_NO);
        payment.setOrderId(10001L);
        payment.setUserId(1L);
        payment.setAmount(new BigDecimal(amount));
        payment.setStatus(0);
        return payment;
    }

    /** 构造未签名通知参数（变更字段后需重新调用 signNotify 保证签名有效） */
    private Map<String, String> notifyParams(String tradeStatus, String totalAmount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("notify_id", "NID-20261017-001");
        params.put("notify_type", "trade_status_sync");
        params.put("notify_time", "2026-10-17 10:00:00");
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_no", TRADE_NO);
        params.put("trade_status", tradeStatus);
        params.put("total_amount", totalAmount);
        params.put("app_id", APP_ID);
        params.put("seller_id", SELLER_ID);
        params.put("subject", "小红书订单");
        params.put("sign_type", "RSA2");
        return params;
    }

    /** 用支付宝私钥签名（模拟服务器侧行为） */
    private Map<String, String> signNotify(Map<String, String> params) {
        params.put("sign", AlipaySignatureUtil.sign(params, alipayPrivateKeyB64));
        return params;
    }

    private String postNotify(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/payment/notify/alipay")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.param(entry.getKey(), entry.getValue());
        }
        return mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.oneOf("success", "fail")))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("TRADE_SUCCESS：验签+二次校验通过 → 成功链路 + 明文 success")
    void tradeSuccessProcessed() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        String ack = postNotify(signNotify(notifyParams("TRADE_SUCCESS", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        verify(paymentService).handlePayCallback(PAYMENT_NO, TRADE_NO, true);
    }

    @Test
    @DisplayName("TRADE_FINISHED：映射为支付成功（本地无不可退款概念，差异注释记录）")
    void tradeFinishedMappedToSuccess() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        String ack = postNotify(signNotify(notifyParams("TRADE_FINISHED", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        verify(paymentService).handlePayCallback(PAYMENT_NO, TRADE_NO, true);
    }

    @Test
    @DisplayName("验签失败（签名被篡改）→ fail + 不处理")
    void signatureFailureRejected() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));
        Map<String, String> params = signNotify(notifyParams("TRADE_SUCCESS", "199.00"));
        params.put("total_amount", "0.01"); // 篡改（签名失配）

        String ack = postNotify(params);

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("二次校验失败：支付单不存在 → fail + 不处理")
    void unknownPaymentRejected() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(null);

        String ack = postNotify(signNotify(notifyParams("TRADE_SUCCESS", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("二次校验失败：金额不符 → fail + 不处理")
    void amountMismatchRejected() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        // 通知金额 0.01（签名有效，但二次校验不过）
        String ack = postNotify(signNotify(notifyParams("TRADE_SUCCESS", "0.01")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("二次校验失败：商户不符（app_id 伪造，签名有效）→ fail + 不处理")
    void merchantMismatchRejected() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));
        Map<String, String> params = notifyParams("TRADE_SUCCESS", "199.00");
        params.put("app_id", "attacker-app");
        signNotify(params); // 用支付宝私钥重签——模拟"签名合法但商户不符"的异常通知

        String ack = postNotify(params);

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("TRADE_CLOSED（本地待支付=未付款关闭）→ 置失败分流 + success")
    void tradeClosedDispatchesFail() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        String ack = postNotify(signNotify(notifyParams("TRADE_CLOSED", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        // 置失败分流：本地待支付 → handlePayCallback(false)（service 内部守卫：已成功/已退款单幂等 no-op）
        verify(paymentService).handlePayCallback(PAYMENT_NO, TRADE_NO, false);
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY：等待付款态 → 忽略 + success")
    void waitBuyerPayIgnored() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        String ack = postNotify(signNotify(notifyParams("WAIT_BUYER_PAY", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("未知 trade_status（签名有效）→ fail + 不处理")
    void unknownTradeStatusRejected() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));

        String ack = postNotify(signNotify(notifyParams("TRADE_WHAT", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("处理异常 → fail（触发渠道重试，不误应答 success）")
    void processingExceptionReturnsFail() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));
        org.mockito.Mockito.doThrow(new RuntimeException("DB 挂了"))
                .when(paymentService).handlePayCallback(any(), any(), anyBoolean());

        String ack = postNotify(signNotify(notifyParams("TRADE_SUCCESS", "199.00")));

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
    }

    @Test
    @DisplayName("重复通知（重试同 notify_id）→ 幂等应答 success（状态守卫在 service 层）")
    void duplicateNotifyIdempotent() throws Exception {
        when(paymentService.findPaymentByNo(PAYMENT_NO)).thenReturn(localPayment("199.00"));
        Map<String, String> params = signNotify(notifyParams("TRADE_SUCCESS", "199.00"));

        String first = postNotify(params);
        String second = postNotify(params);

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo("success");
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo("success");
        verify(paymentService, org.mockito.Mockito.times(2))
                .handlePayCallback(PAYMENT_NO, TRADE_NO, true);
    }
}
