package com.myxhs.payment.controller;

import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.payment.service.PaymentService;
import com.myxhs.payment.simulator.SimulatedAlipayServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付控制器测试触发口单测（C-003）
 * <p>
 * 验证 /callback/{payType} 语义升级：支付宝(1) → 触发模拟服务器走真实通知链路；
 * Mock(99) → 兼容直调；其余渠道 → 拒绝。
 * </p>
 */
class PaymentControllerTest {

    private PaymentService paymentService;
    private AccessTokenGuard accessTokenGuard;
    private SimulatedAlipayServer simulatedAlipayServer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        accessTokenGuard = mock(AccessTokenGuard.class);
        simulatedAlipayServer = mock(SimulatedAlipayServer.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PaymentController(paymentService, accessTokenGuard, simulatedAlipayServer))
                .build();
    }

    private String postCallback(int payType, String body) throws Exception {
        return mockMvc.perform(post("/api/payment/callback/" + payType)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.oneOf("success", "fail")))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("支付宝(1)：触发模拟服务器走真实通知链路（不再直调 service）")
    void alipayTriggerGoesRealChain() throws Exception {
        when(accessTokenGuard.isInternalCall(any())).thenReturn(true);

        String ack = postCallback(1,
                "{\"out_trade_no\":\"PAY1\",\"trade_no\":\"T1\",\"status\":\"SUCCESS\"}");

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        verify(simulatedAlipayServer).triggerPay("PAY1", true);
        verify(paymentService, never()).handlePayCallback(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Mock(99)：兼容直调（同步渠道无渠道交易，保留原语义）")
    void mockChannelDirectCompat() throws Exception {
        when(accessTokenGuard.isInternalCall(any())).thenReturn(true);

        String ack = postCallback(99,
                "{\"out_trade_no\":\"PAY1\",\"trade_no\":\"T1\",\"status\":\"SUCCESS\"}");

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("success");
        verify(paymentService).handlePayCallback("PAY1", "T1", true);
        verify(simulatedAlipayServer, never()).triggerPay(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("非内部调用 → fail（测试触发口保留内部守卫）")
    void nonInternalCallRejected() throws Exception {
        when(accessTokenGuard.isInternalCall(any())).thenReturn(false);

        String ack = postCallback(1, "{\"out_trade_no\":\"PAY1\",\"status\":\"SUCCESS\"}");

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(simulatedAlipayServer, never()).triggerPay(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("不支持的渠道（微信已移除）→ fail")
    void unsupportedChannelRejected() throws Exception {
        when(accessTokenGuard.isInternalCall(any())).thenReturn(true);

        String ack = postCallback(2,
                "{\"out_trade_no\":\"PAY1\",\"trade_no\":\"T1\",\"status\":\"SUCCESS\"}");

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(paymentService, never()).handlePayCallback(anyString(), anyString(), anyBoolean());
        verify(simulatedAlipayServer, never()).triggerPay(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("回调体无法解析（缺 out_trade_no）→ fail")
    void malformedBodyRejected() throws Exception {
        when(accessTokenGuard.isInternalCall(any())).thenReturn(true);

        String ack = postCallback(1, "{\"trade_no\":\"T1\",\"status\":\"SUCCESS\"}");

        org.assertj.core.api.Assertions.assertThat(ack).isEqualTo("fail");
        verify(simulatedAlipayServer, never()).triggerPay(anyString(), anyBoolean());
    }
}
