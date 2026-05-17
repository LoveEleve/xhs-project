package com.myxhs.coupon.validator;

import com.myxhs.coupon.entity.CouponTemplate;

import java.math.BigDecimal;

/**
 * 优惠券使用校验器接口（责任链模式）
 * <p>
 * 用券时按 Order 顺序逐个执行校验。
 * 任何一个校验器抛出异常，则用券失败。
 * </p>
 */
public interface CouponValidator {

    /**
     * 校验优惠券是否可用
     *
     * @param template    券模板
     * @param orderAmount 订单金额
     * @throws com.myxhs.common.exception.BizException 校验不通过时抛出
     */
    void validate(CouponTemplate template, BigDecimal orderAmount);
}
