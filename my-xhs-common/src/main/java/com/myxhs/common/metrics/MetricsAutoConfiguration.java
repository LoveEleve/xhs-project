package com.myxhs.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

/**
 * Micrometer 指标自动配置
 * <p>
 * 核心职责：为所有指标注入公共标签（application / instance）
 * </p>
 */
@Slf4j
@Configuration
public class MetricsAutoConfiguration {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer() {
        return registry -> {
            String instanceId = getInstanceId();
            registry.config()
                    .commonTags(
                            "application", applicationName,
                            "instance", instanceId
                    );
            log.info("[监控] Micrometer 公共标签注入: application={}, instance={}", applicationName, instanceId);
        };
    }

    private String getInstanceId() {
        try {
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            return hostAddress + ":" + serverPort;
        } catch (Exception e) {
            return "unknown:" + serverPort;
        }
    }
}
