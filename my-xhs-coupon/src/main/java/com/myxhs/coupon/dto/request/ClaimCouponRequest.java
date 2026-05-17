package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 领券请求
 */
@Data
public class ClaimCouponRequest {

    /** 优惠券模板ID */
    @NotNull(message = "优惠券ID不能为空")
    private Long templateId;
}
