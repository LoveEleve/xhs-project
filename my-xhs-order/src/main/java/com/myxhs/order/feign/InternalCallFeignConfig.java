package com.myxhs.order.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Feign 内部调用配置 — 自动添加 X-Internal-Call Header
 * <p>
 * order → inventory/release + order → coupon/return 等内部接口需 X-Internal-Call 校验，
 * 此配置确保所有 Feign 调用自动携带正确的内部调用令牌。
 * 令牌通过配置项 myxhs.internal.token 注入（不再硬编码）。
 * </p>
 */
public class InternalCallFeignConfig {

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @jakarta.annotation.PostConstruct
    public void validateInternalToken() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("myxhs.internal.token 未配置，拒绝启动 order 服务");
        }
    }

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return (RequestTemplate template) -> template.header("X-Internal-Call", internalToken);
    }
}
