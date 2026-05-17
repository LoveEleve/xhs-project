package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.entity.CouponTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 有效期校验器（责任链第 2 环）
 * <p>
 * 实时校验券的有效期，不依赖 status 字段。
 * 即使定时任务还没来得及标记过期，这里也能拦截。
 * </p>
 */
@Component
@Order(2)
public class ExpireValidator implements CouponValidator {

    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getValidStart())) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券尚未生效");
        }
        if (now.isAfter(template.getValidEnd())) {
            throw new BizException(ResultCode.COUPON_EXPIRED, "优惠券已过期");
        }
    }
}
