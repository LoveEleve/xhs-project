package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 领券请求
 */
@Data
public class ClaimCouponRequest {

    /** 优惠券模板ID */
    @NotNull(message = "优惠券ID不能为空")
    @Positive(message = "优惠券ID必须为正数")
    private Long templateId;
}
