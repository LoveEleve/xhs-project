package com.myxhs.coupon.validator;

import com.myxhs.coupon.dto.request.CreateTemplateRequest;

/**
 * 优惠券模板创建校验器（责任链模式，C-017）
 * <p>按 @Order 顺序执行；任一校验失败抛 BizException。</p>
 */
public interface TemplateCreateValidator {
    void validate(CreateTemplateRequest request);
}
