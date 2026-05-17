package com.myxhs.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 退款请求
 */
@Data
public class RefundRequest {

    /** 支付单ID（通过支付单关联订单） */
    @NotNull(message = "支付单ID不能为空")
    private Long paymentId;

    /** 用户ID（由网关透传，不需要前端传） */
    private Long userId;

    /** 退款金额 */
    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须为正数")
    private BigDecimal refundAmount;

    /** 退款原因 */
    private String reason;

    /** 退款类型：1-仅退款 2-退货退款 */
    private Integer refundType;
}
