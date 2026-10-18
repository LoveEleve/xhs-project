package com.myxhs.payment.alipay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 退款同步结果（alipay.trade.refund 语义）
 * <p>
 * 关键契约差异：<b>支付宝退款是同步返回</b>（调用即得结果），不是异步通知。
 * <ul>
 *   <li>{@code fund_change}：资金是否发生变化（官方字段）——Y 表示退款成功</li>
 *   <li>失败重试必须携带<b>同一个 out_request_no</b>（"同号只退一次"，见幂等键）</li>
 *   <li>错误码示例：ACQ.TRADE_HAS_CLOSE（交易已关闭不可退）/ ACQ.TRADE_NOT_ALLOW_REFUND / ACQ.SYSTEM_ERROR（可重试）</li>
 * </ul>
 * 调用异常（超时/网络）语义 = "结果未知"，绝不等于失败——上层停留"退款中"，恢复任务同键重试/查询核对。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundTradeResult {

    /** 资金是否发生变化（官方 fund_change=Y 表示退款成功） */
    private boolean fundChange;

    /** 支付宝退款交易号（成功时） */
    private String tradeNo;

    /** 失败码（失败时；可重试类如 ACQ.SYSTEM_ERROR 由上层恢复任务同键重试） */
    private String errorCode;

    /** 失败描述 */
    private String errorMsg;

    public static RefundTradeResult success(String tradeNo) {
        return new RefundTradeResult(true, tradeNo, null, null);
    }

    public static RefundTradeResult fail(String errorCode, String errorMsg) {
        return new RefundTradeResult(false, null, errorCode, errorMsg);
    }
}
