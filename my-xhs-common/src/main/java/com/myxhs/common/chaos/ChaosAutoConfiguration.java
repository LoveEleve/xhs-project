package com.myxhs.common.chaos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 混沌工程自动配置
 * <p>
 * 只有 chaos.enabled=true 时才注册 AOP 拦截器，
 * 生产环境默认不加载，零开销。
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "chaos", name = "enabled", havingValue = "true")
    public ChaosInterceptor chaosInterceptor(ChaosProperties chaosProperties) {
        log.warn("[混沌工程] ⚠️ 故障注入已启用！配置的故障数: {}", chaosProperties.getFaults().size());
        return new ChaosInterceptor(chaosProperties);
    }
}
