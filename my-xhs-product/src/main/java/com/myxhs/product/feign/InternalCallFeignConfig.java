package com.myxhs.product.feign;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端内部调用头配置（与 analytics/cart/content 同款）
 * <p>
 * FeignClientFactoryBean 对 properties 配置类可能 new 实例化导致 @Value 不注入；
 * 本配置由 Feign 子上下文管理，拦截器 new 实例化时读取环境变量兜底。
 * </p>
 */
@Configuration
public class InternalCallFeignConfig {

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return new com.myxhs.feign.config.FeignInternalCallInterceptor();
    }
}
