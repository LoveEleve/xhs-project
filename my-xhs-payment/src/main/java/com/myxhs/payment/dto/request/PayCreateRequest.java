package com.myxhs.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建支付请求
 */
@Data
public class PayCreateRequest {

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 用户ID（由网关透传，不需要前端传） */
    private Long userId;

    /** 支付金额 */
    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须为正数")
    private BigDecimal amount;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    @NotNull(message = "支付方式不能为空")
    private Integer payType;
}
