package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.entity.CouponTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 门槛校验器（责任链第 1 环）
 * <p>
 * 校验订单金额是否满足优惠券的最低消费门槛。
 * 无门槛券（type=3）的 minAmount=0，天然通过。
 * </p>
 */
@Component
@Order(1)
public class AmountValidator implements CouponValidator {

    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        if (template.getMinAmount() != null
                && template.getMinAmount().compareTo(BigDecimal.ZERO) > 0
                && orderAmount.compareTo(template.getMinAmount()) < 0) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE,
                    String.format("订单金额%.2f未达使用门槛%.2f",
                            orderAmount, template.getMinAmount()));
        }
    }
}
