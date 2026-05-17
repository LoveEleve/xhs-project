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
 * <p>
 * 公共标签的作用：
 * - Prometheus 查询时可以按 application 过滤：rate(myxhs_order_create_total{application="my-xhs-order"}[5m])
 * - Grafana Dashboard 可以用 $application 变量做下拉选择
 * - 多实例部署时，通过 instance 标签区分不同实例
 * </p>
 */
@Slf4j
@Configuration
public class MetricsAutoConfiguration {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * 为所有指标注入公共标签
     * <p>
     * 每个指标都会自动携带 application 和 instance 标签：
     * myxhs_order_create_total{application="my-xhs-order", instance="192.168.1.100:19011", status="success"} 42
     * </p>
     */
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

    /**
     * 获取实例标识（IP:Port）
     */
    private String getInstanceId() {
        try {
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            return hostAddress + ":" + serverPort;
        } catch (Exception e) {
            return "unknown:" + serverPort;
        }
    }
}
