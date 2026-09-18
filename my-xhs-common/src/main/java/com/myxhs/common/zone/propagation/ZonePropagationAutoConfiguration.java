package com.myxhs.common.zone.propagation;

import feign.RequestInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Zone 传播自动配置（默认关）。
 * <p>开启后：入站读 X-Zone（Servlet 过滤器），出站带 X-Zone（Feign 拦截器）。</p>
 *
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myxhs.availability.zone.propagation", name = "enabled", havingValue = "true")
public class ZonePropagationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZonePropagationFilter zonePropagationFilter(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new ZonePropagationFilter(meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnClass(RequestInterceptor.class)
    @ConditionalOnMissingBean
    public ZonePropagationInterceptor zonePropagationInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new ZonePropagationInterceptor(meterRegistryProvider.getIfAvailable());
    }
}
