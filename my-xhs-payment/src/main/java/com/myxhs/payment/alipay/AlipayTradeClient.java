package com.myxhs.payment.alipay;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付宝交易客户端（商户侧 SDK 形态的契约层）
 * <p>
 * 本接口是<b>渠道真实契约</b>的抽象：方法名与语义对齐官方 OpenAPI——
 * <ul>
 *   <li>{@code tradeCreate} → alipay.trade.create：创建交易（真实场景返回支付参数/表单）</li>
 *   <li>{@code tradeQuery} → alipay.trade.query：查单（通知的"双保险"另一半）</li>
 *   <li>{@code tradeClose} → alipay.trade.close：关单（换渠道/超时处置）</li>
 *   <li>{@code tradeRefund} → alipay.trade.refund：退款（<b>同步返回</b> + out_request_no 幂等）</li>
 *   <li>{@code verifyNotify} → 异步通知验签（RSA2 + 二次校验由端点完成）</li>
 * </ul>
 * 实现策略（C-003 决策）：请求方向（本接口）由 {@code AlipayTradeClientImpl} 委托本地
 * "模拟支付宝服务器"（进程内 + 真实签名验签）；通知方向（渠道→商户）为真实 HTTP POST + ACK + 重试。
 * 接入真实支付宝时：替换实现为官方 SDK 封装（接口与业务链路不变），仅更换密钥与网关地址。
 * </p>
 */
public interface AlipayTradeClient {

    /**
     * 创建交易（alipay.trade.create 语义）。
     *
     * @param orderId    业务订单号（模拟服务器记账用）
     * @param amount     订单金额
     * @param subject    订单标题
     * @param outTradeNo 商户订单号（本项目 = paymentNo）
     * @return 支付宝交易号（trade_no）
     */
    String tradeCreate(Long orderId, BigDecimal amount, String subject, String outTradeNo);

    /**
     * 查询交易（alipay.trade.query 语义）。
     *
     * @param outTradeNo 商户订单号
     * @return 查单结果；<b>{@code null} = 渠道不可达</b>（"不知道"≠"没付"，调用方不得据此置失败）
     */
    AlipayTradeQueryResult tradeQuery(String outTradeNo);

    /**
     * 关闭交易（alipay.trade.close 语义）。
     * <p>注意官方换单号指引：关闭前应先 query——已支付交易不可关闭（ACQ.TRADE_HAS_SUCCESS），
     * 此时应"受理已付"而不是重发新单（"先查后决"，见换渠道分支）。</p>
     *
     * @param outTradeNo 商户订单号
     * @return 是否关闭成功（交易不存在/已关闭按幂等成功处理）
     */
    boolean tradeClose(String outTradeNo);

    /**
     * 退款（alipay.trade.refund 语义——<b>同步返回</b>）。
     * <p>失败重试必须携带同一个 {@code outRequestNo}（"同号只退一次"）。</p>
     *
     * @param outTradeNo  商户订单号
     * @param outRequestNo 退款请求号（本项目 = out_refund_no）
     * @param amount      退款金额
     * @param reason      退款原因
     * @return 同步退款结果（fund_change 表示资金是否发生变化）
     */
    RefundTradeResult tradeRefund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason);

    /**
     * 异步通知验签（RSA2，使用支付宝公钥）。
     *
     * @param params 通知原始参数（必须原样，不得增删）
     * @return 验签是否通过
     */
    boolean verifyNotify(Map<String, String> params);
}
