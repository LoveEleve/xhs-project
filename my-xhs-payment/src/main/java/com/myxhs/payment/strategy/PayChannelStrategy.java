package com.myxhs.payment.strategy;

import com.myxhs.payment.alipay.RefundTradeResult;

import java.math.BigDecimal;

/**
 * 支付渠道策略接口
 * <p>
 * 策略模式：每种支付方式实现此接口，通过 {@link com.myxhs.payment.config.PayStrategyConfig}
 * 注册到 Map 中，由 {@link com.myxhs.payment.service.PaymentService} 按支付类型选择。
 * </p>
 * <p>
 * 渠道契约（C-003）：
 * <ul>
 *   <li>发起支付：异步渠道（支付宝）创建交易后由渠道通知驱动状态推进；Mock 为同步成功</li>
 *   <li>退款：<b>同步返回结果</b>（支付宝真实契约）——成功/失败由返回值表达，不再有退款回调</li>
 *   <li>查单：渠道事实来源（"不知道"≠"没付"：不可达返回 null）</li>
 *   <li>关单：抛异常 = 渠道未确认关闭（调用方按"先查后决"处理）</li>
 * </ul>
 * </p>
 */
public interface PayChannelStrategy {

    /**
     * 发起支付。
     *
     * @param orderId   订单 ID
     * @param amount    支付金额
     * @param paymentNo 支付流水号（= 渠道侧商户订单号 out_trade_no）
     * @return 渠道交易号（异步通知中回传）；Mock 模式返回 paymentNo
     */
    String pay(Long orderId, BigDecimal amount, String paymentNo);

    /**
     * 退款（<b>同步返回</b>）。
     * <p>
     * 支付宝真实契约：调用即得结果（fund_change）。失败重试必须携带同一个
     * {@code outRequestNo}（"同号只退一次"）。调用抛异常 = 结果未知——
     * 上层绝不当作失败（事务回滚后由调用方同键重试，渠道幂等兜底）。
     * </p>
     *
     * @param paymentNo    支付流水号（= 渠道侧 out_trade_no）
     * @param outRequestNo 退款请求号（渠道幂等键：优先业务键 outRefundNo，缺省用 refundNo）
     * @param refundAmount 退款金额
     * @param reason       退款原因
     * @return 同步退款结果（fundChange=true 表示退款成功）
     */
    RefundTradeResult refund(String paymentNo, String outRequestNo, BigDecimal refundAmount, String reason);

    /**
     * 查询支付状态（补偿/对账/超时处置/换渠道"先查"）。
     *
     * @param paymentNo 支付流水号
     * @return 0=渠道确认未支付 1=渠道已支付；{@code null}=不可达/不可判定（绝不据此置失败）
     */
    Integer queryPayStatus(String paymentNo);

    /**
     * 关闭渠道交易（换渠道关旧 / 超时处置 / 订单终结）。
     * <p>
     * 语义约定：调用方负责本地状态推进；本方法抛异常表示"渠道未确认关闭"——
     * 换渠道场景应中止切换（先查后决：复查渠道真相后决定受理或报错）。
     * </p>
     *
     * @param paymentNo 支付流水号
     */
    void close(String paymentNo);
}
