package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用券请求（订单服务 Feign 调用）
 */
@Data
public class UseCouponRequest {

    /** 用户优惠券记录ID */
    @NotNull(message = "用户券ID不能为空")
    @Positive(message = "用户券ID必须为正数")
    private Long userCouponId;

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    @Positive(message = "订单ID必须为正数")
    private Long orderId;

    /** 订单金额（用于门槛校验） */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于0")
    private BigDecimal orderAmount;
}
