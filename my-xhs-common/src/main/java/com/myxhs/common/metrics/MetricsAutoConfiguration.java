package com.myxhs.common.metrics;

import com.myxhs.common.mq.DlqMessageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

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

    private final ObjectProvider<BusinessMetrics> businessMetricsProvider;

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Value("${server.port:8080}")
    private int serverPort;

    public MetricsAutoConfiguration(ObjectProvider<BusinessMetrics> businessMetricsProvider) {
        this.businessMetricsProvider = businessMetricsProvider;
    }

    /**
     * 容器刷新完成后将 BusinessMetrics 注入到 DlqMessageHandler
     * 使用事件监听而非 @PostConstruct，避免与 MeterRegistry 的创建形成循环依赖
     */
    @EventListener(ContextRefreshedEvent.class)
    public void initDlqMetrics() {
        BusinessMetrics bm = businessMetricsProvider.getIfAvailable();
        if (bm != null) {
            DlqMessageHandler.setBusinessMetrics(bm);
            log.info("[监控] DlqMessageHandler 已注入 BusinessMetrics");
        }
    }

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
