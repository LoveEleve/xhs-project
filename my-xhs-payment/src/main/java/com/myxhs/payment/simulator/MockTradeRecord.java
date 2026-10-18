package com.myxhs.payment.simulator;

import lombok.Data;

/**
 * 模拟支付宝服务器的交易台账记录（C-003）
 * <p>
 * 台账是"模拟渠道"的<b>交易事实</b>：query/close/refund/通知参数构造的唯一事实来源。
 * 存储形态：Redis String（JSON）——`myxhs:payment:mock-alipay:trade:{outTradeNo}`。
 * </p>
 */
@Data
public class MockTradeRecord {

    /** 商户订单号（= 本项目 paymentNo） */
    private String outTradeNo;

    /** 支付宝交易号 */
    private String tradeNo;

    /** 业务订单号 */
    private Long orderId;

    /** 订单金额（字符串存储，比较用 BigDecimal） */
    private String amount;

    /** 已退款累计（全退判定与超额退款防护） */
    private String refundedAmount;

    /** 订单标题 */
    private String subject;

    /** 交易状态（AlipayTradeStatus.name()） */
    private String status;

    /** 当前通知的 notify_id（生成一次、重试不变） */
    private String notifyId;

    /** 当前通知是否已被 ACK（应答明文 success） */
    private boolean notifyDelivered;

    /** 当前通知已投递次数 */
    private int notifyAttempts;

    /** 下次重试时间（epoch 毫秒；0 = 无需重试） */
    private long nextRetryAt;

    /** "用户付款"模拟触发时间（epoch 毫秒；到点后按成功率转成功） */
    private long payAfterAt;

    /** 自动付款是否已掷骰（一次性——失败即"用户未完成支付"，停留 WAIT 等超时处置） */
    private boolean autoPayRolled;

    /** 创建时间（epoch 毫秒） */
    private long createdAt;
}
