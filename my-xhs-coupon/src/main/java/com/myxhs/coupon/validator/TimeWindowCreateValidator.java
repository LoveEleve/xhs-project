package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 创建校验 1：有效期窗口 */
@Component
@Order(1)
public class TimeWindowCreateValidator implements TemplateCreateValidator {

    @Override
    public void validate(CreateTemplateRequest request) {
        if (request.getValidEnd().isBefore(request.getValidStart())) {
            throw new BizException(ResultCode.PARAM_INVALID, "有效期结束时间必须晚于开始时间");
        }
        if (!request.getValidEnd().isAfter(LocalDateTime.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "有效期结束时间必须晚于当前时间");
        }
    }
}
