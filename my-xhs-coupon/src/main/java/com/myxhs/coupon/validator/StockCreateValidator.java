package com.myxhs.coupon.validator;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 创建校验 3：库存与限领 */
@Component
@Order(3)
public class StockCreateValidator implements TemplateCreateValidator {

    private static final int MAX_TOTAL = 1_000_000;

    @Override
    public void validate(CreateTemplateRequest request) {
        if (request.getTotalCount() > MAX_TOTAL) {
            throw new BizException(ResultCode.PARAM_INVALID, "发放总量不能超过" + MAX_TOTAL);
        }
        if (request.getPerUserLimit() > request.getTotalCount()) {
            throw new BizException(ResultCode.PARAM_INVALID, "每人限领不能超过发放总量");
        }
    }
}
