package com.myxhs.payment.alipay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约模型单测（四态枚举 / 通知参数 / 退款结果）
 */
class AlipayContractModelsTest {

    @Test
    @DisplayName("trade_status 四态解析；未知/空 → null（不可判定，不猜测）")
    void tradeStatusFromCode() {
        assertThat(AlipayTradeStatus.fromCode("WAIT_BUYER_PAY")).isEqualTo(AlipayTradeStatus.WAIT_BUYER_PAY);
        assertThat(AlipayTradeStatus.fromCode("TRADE_SUCCESS")).isEqualTo(AlipayTradeStatus.TRADE_SUCCESS);
        assertThat(AlipayTradeStatus.fromCode("TRADE_FINISHED")).isEqualTo(AlipayTradeStatus.TRADE_FINISHED);
        assertThat(AlipayTradeStatus.fromCode("TRADE_CLOSED")).isEqualTo(AlipayTradeStatus.TRADE_CLOSED);
        assertThat(AlipayTradeStatus.fromCode("TRADE_UNKNOWN")).isNull();
        assertThat(AlipayTradeStatus.fromCode(null)).isNull();
        assertThat(AlipayTradeStatus.fromCode("  ")).isNull();
    }

    @Test
    @DisplayName("isPaid：SUCCESS/FINISHED = 支付成功语义；WAIT/CLOSED = 否")
    void tradeStatusIsPaid() {
        assertThat(AlipayTradeStatus.TRADE_SUCCESS.isPaid()).isTrue();
        assertThat(AlipayTradeStatus.TRADE_FINISHED.isPaid()).isTrue();
        assertThat(AlipayTradeStatus.WAIT_BUYER_PAY.isPaid()).isFalse();
        assertThat(AlipayTradeStatus.TRADE_CLOSED.isPaid()).isFalse();
    }

    @Test
    @DisplayName("通知参数解析：字段映射 + raw 原样保留（验签依据）+ status() 解析")
    void notifyParamsFromMap() {
        Map<String, String> params = new HashMap<>();
        params.put("notify_id", "NID-001");
        params.put("notify_type", "trade_status_sync");
        params.put("out_trade_no", "PAY20261017001");
        params.put("trade_no", "ALIPAY_TRADE_001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "199.00");
        params.put("app_id", "mock-app-2026");
        params.put("seller_id", "mock-seller-2026");
        params.put("sign", "some-sign");
        params.put("sign_type", "RSA2");

        AlipayNotifyParams parsed = AlipayNotifyParams.fromMap(params);

        assertThat(parsed.getNotifyId()).isEqualTo("NID-001");
        assertThat(parsed.getOutTradeNo()).isEqualTo("PAY20261017001");
        assertThat(parsed.getTradeNo()).isEqualTo("ALIPAY_TRADE_001");
        assertThat(parsed.getTotalAmount()).isEqualTo("199.00");
        assertThat(parsed.getAppId()).isEqualTo("mock-app-2026");
        assertThat(parsed.getSellerId()).isEqualTo("mock-seller-2026");
        assertThat(parsed.status()).isEqualTo(AlipayTradeStatus.TRADE_SUCCESS);
        // raw 原样保留（验签必须基于原样参数，不得基于模型字段重建）
        assertThat(parsed.getRaw()).containsExactlyInAnyOrderEntriesOf(params);
    }

    @Test
    @DisplayName("通知参数：空 map / 未知状态不抛错")
    void notifyParamsEdgeCases() {
        AlipayNotifyParams empty = AlipayNotifyParams.fromMap(null);
        assertThat(empty.getRaw()).isEmpty();
        assertThat(empty.status()).isNull();

        Map<String, String> params = new HashMap<>();
        params.put("trade_status", "WHAT_IS_THIS");
        assertThat(AlipayNotifyParams.fromMap(params).status()).isNull();
    }

    @Test
    @DisplayName("退款结果工厂：success（fund_change=true）/ fail（错误码）")
    void refundTradeResultFactories() {
        RefundTradeResult success = RefundTradeResult.success("ALIPAY_REFUND_001");
        assertThat(success.isFundChange()).isTrue();
        assertThat(success.getTradeNo()).isEqualTo("ALIPAY_REFUND_001");
        assertThat(success.getErrorCode()).isNull();

        RefundTradeResult fail = RefundTradeResult.fail("ACQ.SYSTEM_ERROR", "系统繁忙，请重试");
        assertThat(fail.isFundChange()).isFalse();
        assertThat(fail.getErrorCode()).isEqualTo("ACQ.SYSTEM_ERROR");
        assertThat(fail.getTradeNo()).isNull();
    }
}
