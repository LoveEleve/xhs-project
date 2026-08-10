package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 退券请求（取消订单时调用）
 */
@Data
public class ReturnCouponRequest {

    /** 用户优惠券记录ID */
    @NotNull(message = "用户券ID不能为空")
    @Positive(message = "用户券ID必须为正数")
    private Long userCouponId;

    /** 订单ID（校验是否是该订单使用的券） */
    @NotNull(message = "订单ID不能为空")
    @Positive(message = "订单ID必须为正数")
    private Long orderId;
}
