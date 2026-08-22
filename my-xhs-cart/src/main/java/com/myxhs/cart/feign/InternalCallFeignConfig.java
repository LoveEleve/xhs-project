package com.myxhs.cart.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Feign 内部调用配置 — 自动添加 X-Internal-Call Header
 * <p>
 * cart → product 的批量查询接口需要 X-Internal-Call 校验，
 * 此配置确保所有 Feign 调用自动携带正确的内部调用令牌。
 * 令牌通过配置项 myxhs.internal.token 注入（不再硬编码）。
 * </p>
 */
public class InternalCallFeignConfig {

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @PostConstruct
    public void validateInternalToken() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("myxhs.internal.token 未配置，拒绝启动 cart 服务");
        }
    }

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return (RequestTemplate template) -> template.header("X-Internal-Call", internalToken);
    }
}
