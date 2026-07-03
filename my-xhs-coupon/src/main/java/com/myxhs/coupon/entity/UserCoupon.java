package com.myxhs.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户优惠券实体
 * <p>
 * 对应表 t_user_coupon。
 * 状态流转：0(未使用) → 1(已使用) / 2(已过期)
 * 已使用的券可以退回（取消订单时）：1 → 0
 * </p>
 */
@Data
@TableName("t_user_coupon")
public class UserCoupon implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 优惠券模板ID */
    private Long couponId;

    /** 领券流水号（MQ msgId），用于幂等去重，支持 perUserLimit > 1 */
    private String claimNo;

    /** 状态：0-未使用 1-已使用 2-已过期 */
    private Integer status;

    /** 使用的订单ID */
    private Long usedOrderId;

    /** 领取时间 */
    private LocalDateTime receivedAt;

    /** 使用时间 */
    private LocalDateTime usedAt;
}
