package com.myxhs.payment.alipay;

/**
 * 支付宝交易状态（trade_status 四态）
 * <p>
 * 官方契约（异步通知与 alipay.trade.query 共用同一枚举）：
 * <ul>
 *   <li>{@code WAIT_BUYER_PAY}：交易创建，等待买家付款</li>
 *   <li>{@code TRADE_SUCCESS}：交易支付成功（可退款）</li>
 *   <li>{@code TRADE_FINISHED}：交易结束，不可退款（如超过退款期）</li>
 *   <li>{@code TRADE_CLOSED}：交易关闭——<b>双语义</b>：未付款超时关闭 / 支付完成后全额退款</li>
 * </ul>
 * 双语义的处置必须按本地状态分流（见通知端点）：
 * 本地"待支付"单收到 TRADE_CLOSED = 未付款关闭；本地"已成功/已退款"单收到 = 全退后关闭（幂等收敛）。
 * </p>
 */
public enum AlipayTradeStatus {

    WAIT_BUYER_PAY,
    TRADE_SUCCESS,
    TRADE_FINISHED,
    TRADE_CLOSED;

    /**
     * 按官方状态码解析。
     *
     * @param code 官方 trade_status 字符串
     * @return 枚举；未知/空 → {@code null}（调用方按"不可判定"处理，绝不猜测）
     */
    public static AlipayTradeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AlipayTradeStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 是否"支付成功"语义（TRADE_SUCCESS / TRADE_FINISHED）。
     * <p>映射到本地支付状态时两者等价（FINISHED 的"不可退款"差异在本地无对应概念，注释记录）。</p>
     */
    public boolean isPaid() {
        return this == TRADE_SUCCESS || this == TRADE_FINISHED;
    }
}
