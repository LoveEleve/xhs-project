package com.myxhs.coupon.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券 VO
 */
@Data
@Builder
public class UserCouponVO {

    /** 用户券记录ID */
    private Long id;

    /** 优惠券模板ID */
    private Long couponId;

    /** 优惠券名称 */
    private String name;

    /** 类型：1-满减 2-折扣 3-无门槛 */
    private Integer type;

    /** 优惠金额/折扣率 */
    private BigDecimal discountValue;

    /** 最低消费金额 */
    private BigDecimal minAmount;

    /** 状态：0-未使用 1-已使用 2-已过期 */
    private Integer status;

    /** 有效期结束 */
    private LocalDateTime validEnd;

    /** 领取时间 */
    private LocalDateTime receivedAt;
}
