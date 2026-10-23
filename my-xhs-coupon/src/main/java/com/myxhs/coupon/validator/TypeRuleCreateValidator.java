package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 创建校验 4：类型规则（满减必须有门槛；无门槛门槛必须为 0） */
@Component
@Order(4)
public class TypeRuleCreateValidator implements TemplateCreateValidator {

    @Override
    public void validate(CreateTemplateRequest request) {
        BigDecimal min = request.getMinAmount();
        if (request.getType() == 1 && (min == null || min.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BizException(ResultCode.PARAM_INVALID, "满减券必须设置大于0的使用门槛");
        }
        if (request.getType() == 3 && min != null && min.compareTo(BigDecimal.ZERO) > 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "无门槛券的使用门槛必须为0");
        }
    }
}
