package com.myxhs.payment.alipay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支付宝查单结果（alipay.trade.query 语义）
 * <p>
 * 与"支付回调"的关系：官方要求"异步通知 + 查单"双保险——
 * 通知可能丢失/重试放弃，查单是最终事实来源（对账核查分支的输入）。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlipayTradeQueryResult {

    /** 支付宝交易号 */
    private String tradeNo;

    /** 交易状态（四态） */
    private AlipayTradeStatus status;
}
