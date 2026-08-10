package com.myxhs.payment.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付服务 → 订单服务内部调用认证拦截器
 * <p>
 * 在 Feign 请求中自动注入 X-Internal-Call 头，
 * 订单服务回调接口通过此头验证调用来源（防外部伪造回调）。
 * </p>
 */
@Configuration
public class InternalCallFeignConfig {

    @org.springframework.beans.factory.annotation.Value("${myxhs.internal.token:}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return (RequestTemplate template) -> {
            String path = template.path();
            if (path != null && (path.contains("pay-success") || path.contains("pay-fail")
                    || path.contains("refund-success") || path.contains("refund-fail")
                    || path.contains("pay-amount"))) {
                template.header("X-Internal-Call", internalToken);
            }
        };
    }
}
