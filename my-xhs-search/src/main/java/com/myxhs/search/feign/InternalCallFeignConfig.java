package com.myxhs.search.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Feign 内部调用配置 — 自动添加 X-Internal-Call Header（search 域，C-004）
 * <p>
 * 镜像 order 域同款实现：令牌经 {@code myxhs.internal.token} 注入（不硬编码），
 * 未配置时拒绝启动（fail-closed）。
 * </p>
 */
public class InternalCallFeignConfig {

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @jakarta.annotation.PostConstruct
    public void validateInternalToken() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("myxhs.internal.token 未配置，拒绝启动 search 服务的内部调用");
        }
    }

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return (RequestTemplate template) -> template.header("X-Internal-Call", internalToken);
    }
}
