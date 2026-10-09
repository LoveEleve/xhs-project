package com.myxhs.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 按订单退款请求（售后场景）
 * <p>
 * 与 {@link RefundRequest} 的区别：上游（订单/售后）只持有 orderId，
 * 不应该感知支付域内部主键 paymentId——由支付域自行解析 orderId → 支付单。
 * </p>
 */
@Data
public class RefundByOrderRequest {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 退款金额 */
    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须为正数")
    private BigDecimal refundAmount;

    /** 退款原因 */
    private String reason;

    /** 退款类型：1-仅退款 2-退货退款 */
    private Integer refundType;
}
