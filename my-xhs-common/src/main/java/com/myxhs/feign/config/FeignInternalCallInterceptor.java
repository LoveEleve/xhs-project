package com.myxhs.feign.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全局 Feign 拦截器——所有 FeignClient 自动注入 X-Internal-Call 头。
 * 使用 @Component 保证对所有 FeignClient 全局生效（不限于特定 configuration 类）。
 */
@Component
public class FeignInternalCallInterceptor implements RequestInterceptor {

    @Value("${myxhs.internal.token:my-xhs-internal-token-2026}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Call", internalToken);
    }
}
