package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.entity.CouponTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 券状态校验器（责任链第 3 环）
 * <p>
 * 校验券模板是否处于启用状态。
 * 管理员可能随时下线某个券模板，此时已领取的券不能再使用。
 * </p>
 */
@Component
@Order(3)
public class StatusValidator implements CouponValidator {

    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        if (template.getStatus() == null || template.getStatus() != 1) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已下线");
        }
    }
}
