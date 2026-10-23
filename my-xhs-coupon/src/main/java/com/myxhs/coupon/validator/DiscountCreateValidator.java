package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 创建校验 2：优惠值规则（折扣券 0.1~9.9；满减/无门槛必须 > 0） */
@Component
@Order(2)
public class DiscountCreateValidator implements TemplateCreateValidator {

    @Override
    public void validate(CreateTemplateRequest request) {
        BigDecimal value = request.getDiscountValue();
        if (request.getType() == 2) {
            if (value.compareTo(new BigDecimal("0.1")) < 0 || value.compareTo(new BigDecimal("9.9")) > 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "折扣值必须在0.1~9.9之间");
            }
        } else if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "优惠金额必须大于0");
        }
    }
}
