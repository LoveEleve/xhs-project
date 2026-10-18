package com.myxhs.payment.alipay;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝异步通知参数模型（form 表单参数）
 * <p>
 * 官方契约（POST form-urlencoded 到 notify_url），关键参数：
 * <ul>
 *   <li>{@code notify_id}：通知校验 ID——<b>重试时不变</b>（可用于识别同一通知的重试）</li>
 *   <li>{@code notify_type}：固定 {@code trade_status_sync}</li>
 *   <li>{@code out_trade_no}：商户订单号（本项目 = paymentNo）</li>
 *   <li>{@code trade_no}：支付宝交易号</li>
 *   <li>{@code trade_status}：四态（见 {@link AlipayTradeStatus}）</li>
 *   <li>{@code total_amount}：订单总金额（<b>二次校验</b>比对项）</li>
 *   <li>{@code app_id} / {@code seller_id}：应用 ID / 商户 PID（<b>二次校验</b>比对项）</li>
 *   <li>{@code sign} / {@code sign_type}：签名与签名算法（RSA2）</li>
 * </ul>
 * {@link #raw} 保存原始参数——验签必须用<b>原样参数</b>，不得基于模型字段重建。
 * </p>
 */
@Data
public class AlipayNotifyParams {

    private String notifyId;
    private String notifyType;
    private String notifyTime;
    private String outTradeNo;
    private String tradeNo;
    private String tradeStatus;
    private String totalAmount;
    private String appId;
    private String sellerId;
    private String subject;
    private String signType;
    private String sign;

    /** 原始参数（验签用——必须原样，不得增删） */
    private Map<String, String> raw = new HashMap<>();

    /**
     * 从 form 参数解析。
     *
     * @param params 通知原始参数（已由容器完成 form 解码）
     */
    public static AlipayNotifyParams fromMap(Map<String, String> params) {
        AlipayNotifyParams p = new AlipayNotifyParams();
        if (params == null) {
            return p;
        }
        p.raw = new HashMap<>(params);
        p.notifyId = params.get("notify_id");
        p.notifyType = params.get("notify_type");
        p.notifyTime = params.get("notify_time");
        p.outTradeNo = params.get("out_trade_no");
        p.tradeNo = params.get("trade_no");
        p.tradeStatus = params.get("trade_status");
        p.totalAmount = params.get("total_amount");
        p.appId = params.get("app_id");
        p.sellerId = params.get("seller_id");
        p.subject = params.get("subject");
        p.signType = params.get("sign_type");
        p.sign = params.get("sign");
        return p;
    }

    /** 解析后的交易状态；未知/空 → {@code null}（不可判定） */
    public AlipayTradeStatus status() {
        return AlipayTradeStatus.fromCode(tradeStatus);
    }
}
