package com.myxhs.payment.strategy.impl;

import com.myxhs.payment.alipay.AlipayTradeClient;
import com.myxhs.payment.alipay.AlipayTradeQueryResult;
import com.myxhs.payment.alipay.AlipayTradeStatus;
import com.myxhs.payment.alipay.RefundTradeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付宝策略单测（契约层适配：四态映射 / 同步退款 / 关单语义）
 */
class AlipayPayStrategyTest {

    private static final String PAYMENT_NO = "PAY20261017001";

    private AlipayTradeClient alipayTradeClient;
    private AlipayPayStrategy strategy;

    @BeforeEach
    void setUp() {
        alipayTradeClient = mock(AlipayTradeClient.class);
        strategy = new AlipayPayStrategy(alipayTradeClient);
    }

    @Test
    @DisplayName("pay：委托契约层 tradeCreate（标题=订单支付）")
    void payDelegatesToTradeCreate() {
        when(alipayTradeClient.tradeCreate(eq(10001L), any(BigDecimal.class), eq("订单支付"), eq(PAYMENT_NO)))
                .thenReturn("ALIPAY_TRADE_001");

        String tradeNo = strategy.pay(10001L, new BigDecimal("199.00"), PAYMENT_NO);

        assertThat(tradeNo).isEqualTo("ALIPAY_TRADE_001");
    }

    @Test
    @DisplayName("queryPayStatus 四态映射：WAIT→0 / SUCCESS|FINISHED→1 / CLOSED→0 / 不可达→null")
    void queryPayStatusMapping() {
        when(alipayTradeClient.tradeQuery("P-WAIT"))
                .thenReturn(new AlipayTradeQueryResult("T", AlipayTradeStatus.WAIT_BUYER_PAY));
        when(alipayTradeClient.tradeQuery("P-SUCCESS"))
                .thenReturn(new AlipayTradeQueryResult("T", AlipayTradeStatus.TRADE_SUCCESS));
        when(alipayTradeClient.tradeQuery("P-FINISHED"))
                .thenReturn(new AlipayTradeQueryResult("T", AlipayTradeStatus.TRADE_FINISHED));
        when(alipayTradeClient.tradeQuery("P-CLOSED"))
                .thenReturn(new AlipayTradeQueryResult("T", AlipayTradeStatus.TRADE_CLOSED));
        when(alipayTradeClient.tradeQuery("P-UNREACHABLE")).thenReturn(null);

        assertThat(strategy.queryPayStatus("P-WAIT")).isEqualTo(0);
        assertThat(strategy.queryPayStatus("P-SUCCESS")).isEqualTo(1);
        assertThat(strategy.queryPayStatus("P-FINISHED")).isEqualTo(1);
        // CLOSED 对"本地 pending"单 = 未付款超时关闭 → 0（可安全置失败）
        assertThat(strategy.queryPayStatus("P-CLOSED")).isEqualTo(0);
        // 不可达/不可判定 → null（"不知道"≠"没付"）
        assertThat(strategy.queryPayStatus("P-UNREACHABLE")).isNull();
    }

    @Test
    @DisplayName("queryPayStatus：状态字段缺失 → null（不可判定）")
    void queryPayStatusNullStatus() {
        when(alipayTradeClient.tradeQuery("P-NULL")).thenReturn(new AlipayTradeQueryResult("T", null));
        assertThat(strategy.queryPayStatus("P-NULL")).isNull();
    }

    @Test
    @DisplayName("refund：委托契约层同步退款（透传 outRequestNo）")
    void refundDelegates() {
        when(alipayTradeClient.tradeRefund("PAY1", "AFTERSALE-1", new BigDecimal("10.00"), "售后退款"))
                .thenReturn(RefundTradeResult.success("R1"));

        RefundTradeResult result = strategy.refund("PAY1", "AFTERSALE-1", new BigDecimal("10.00"), "售后退款");

        assertThat(result.isFundChange()).isTrue();
        verify(alipayTradeClient).tradeRefund("PAY1", "AFTERSALE-1", new BigDecimal("10.00"), "售后退款");
    }

    @Test
    @DisplayName("close：渠道确认关闭 → 正常；不可关闭（已支付）→ 抛异常（调用方先查后决）")
    void closeSemantics() {
        when(alipayTradeClient.tradeClose("P-OK")).thenReturn(true);
        strategy.close("P-OK");

        when(alipayTradeClient.tradeClose("P-PAID")).thenReturn(false);
        assertThatThrownBy(() -> strategy.close("P-PAID"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可关闭");
    }
}
