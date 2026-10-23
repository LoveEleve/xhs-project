package com.myxhs.coupon.constant;

/**
 * 优惠券 MQ 常量（C-017）
 */
public final class CouponMqConstants {

    /** 模板结束延时消息：消费端把该模板未使用券置过期 */
    public static final String COUPON_EXPIRE_TOPIC = "COUPON_EXPIRE_TOPIC";

    private CouponMqConstants() {
    }
}
