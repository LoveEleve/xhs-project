package com.myxhs.feign.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全局 Feign 拦截器——所有 FeignClient 自动注入 X-Internal-Call 头。
 * 使用 @Component 保证对所有 FeignClient 全局生效（不限于特定 configuration 类）。
 * <p>
 * 【安全 P-B4】token 必须由环境变量注入（INTERNAL_TOKEN），默认空 = fail-closed：
 * 未配置时本拦截器不携带该头，对端 GatewayAuthTrustFilter 将剥离 X-User-Id，
 * 内部调用直接失败（宁可链路不可用，不可泄露默认令牌）。
 * <p>
 * 【T-017 修复】FeignClientFactoryBean.getOrInstantiate 对配置类用 Feign 子上下文实例化——
 * 拦截器类名配置（spring.cloud.openfeign.client.config.default.request-interceptors）时
 * 可能 new 实例化（@Value 不注入）→ token 空 → 不带头。字段默认值直接读环境变量，
 * Spring 管理时 @Value 覆盖默认值，双路径均生效。
 */
@Component
public class FeignInternalCallInterceptor implements RequestInterceptor {

    @Value("${myxhs.internal.token:}")
    private String internalToken = initFromEnv();

    private static String initFromEnv() {
        String t = System.getenv("INTERNAL_TOKEN");
        return t == null ? "" : t;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (internalToken != null && !internalToken.isEmpty()) {
            template.header("X-Internal-Call", internalToken);
        }
    }
}
