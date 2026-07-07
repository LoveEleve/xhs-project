package com.myxhs.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 指标自动配置
 * 
 * 仅在 MyBatis 和 Micrometer 都可用时生效。
 * 如果模块没有引入 MyBatis（如 home BFF 层），不会创建此 Bean。
 */
@Configuration
@ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
@ConditionalOnBean(MeterRegistry.class)
public class MyBatisMetricsAutoConfiguration {

    @Bean
    public MyBatisMetricsInterceptor myBatisMetricsInterceptor(MeterRegistry meterRegistry) {
        return new MyBatisMetricsInterceptor(meterRegistry);
    }
}
