package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用券请求（订单服务 Feign 调用）
 */
@Data
public class UseCouponRequest {

    /** 用户优惠券记录ID */
    @NotNull(message = "用户券ID不能为空")
    private Long userCouponId;

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 订单金额（用于门槛校验） */
    @NotNull(message = "订单金额不能为空")
    private BigDecimal orderAmount;
}
