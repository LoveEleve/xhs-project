package com.myxhs.payment.strategy;

/**
 * 支付渠道策略接口
 * <p>
 * 策略模式：每种支付方式（Mock/支付宝/微信）实现此接口。
 * 通过 {@link com.myxhs.payment.config.PayStrategyConfig} 注册到 Map 中，
 * 由 {@link com.myxhs.payment.service.PaymentService#pay(com.myxhs.payment.dto.request.PayCreateRequest)} 
 * 根据支付类型选择对应策略执行。
 * </p>
 * <p>
 * 设计原则：
 * 1. 每个方法对应支付生命周期的一个阶段
 * 2. 异步回调模式：pay() 触发支付请求 → 第三方异步回调 → 回调处理器更新状态
 * 3. Mock模式：pay() 直接返回成功，不走第三方
 * </p>
 */
public interface PayChannelStrategy {

    /**
     * 发起支付
     * <p>
     * 支付宝/微信：向第三方提交支付请求，返回交易号（异步模式）。
     * Mock：直接标记支付成功（同步模式）。
     * </p>
     *
     * @param orderId   订单ID
     * @param amount    支付金额
     * @param paymentNo 支付流水号
     * @return 第三方交易号（异步回调时用于匹配）；Mock模式返回 paymentNo
     */
    String pay(Long orderId, java.math.BigDecimal amount, String paymentNo);

    /**
     * 处理支付回调
     * <p>
     * 第三方支付平台异步通知支付结果时调用。
     * 支付宝/微信：根据回调数据更新支付状态。
     * Mock：不需要处理回调。
     * </p>
     *
     * @param paymentNo   支付流水号
     * @param tradeNo     第三方交易号
     * @param success     是否支付成功
     */
    void handleCallback(String paymentNo, String tradeNo, boolean success);

    /**
     * 退款
     * <p>
     * 支付宝/微信：向第三方提交退款请求。
     * Mock：直接标记退款成功。
     * </p>
     *
     * @param paymentId    支付单ID
     * @param refundNo     退款单号
     * @param refundAmount 退款金额
     * @param reason       退款原因
     * @return 第三方退款单号；Mock模式返回 refundNo
     */
    String refund(Long paymentId, String refundNo, java.math.BigDecimal refundAmount, String reason);

    /**
     * 处理退款回调
     * <p>
     * 第三方支付平台异步通知退款结果时调用。
     * </p>
     *
     * @param refundNo 退款单号
     * @param success  是否退款成功
     */
    void handleRefundCallback(String refundNo, boolean success);

    /**
     * 查询支付状态（用于补偿/对账）
     * <p>
     * 支付宝/微信：主动向第三方查询支付状态。
     * Mock：查数据库即可。
     * </p>
     *
     * @param paymentNo 支付流水号
     * @return 支付状态：0-待支付 1-支付成功 2-支付失败
     */
    Integer queryPayStatus(String paymentNo);

    /**
     * 关闭渠道交易（ITER-002）
     * <p>
     * 使用场景：换渠道（关旧开新）/ 支付超时处置（按渠道真相关闭）/ 订单终结（关闭 pending）。
     * 支付宝/微信：调渠道关闭接口（closeorder / trade.close）；Mock：无真实渠道交易，仅记录。
     * </p>
     * <p>
     * 语义约定：调用方负责本地状态推进；本方法抛异常表示"渠道未确认关闭"，
     * 换渠道场景应中止切换（避免"渠道旧交易仍可支付 + 本地已关闭"的错位）。
     * </p>
     *
     * @param paymentNo 支付流水号
     */
    void close(String paymentNo);
}
