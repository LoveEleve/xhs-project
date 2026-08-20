package com.myxhs.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P1 修复（2026-08-13）：gateway 指标注入 application 公共标签
 * <p>
 * 背景：gateway 不依赖 common 模块（独立实现），MetricsAutoConfiguration 未加载 →
 * 全部指标无 application 标签 → 所有 $application 监控面板看不到网关流量（入口流量缺失）。
 * 修复：本配置注入 application=my-xhs-gateway 公共标签（与其他 14 服务对齐）。
 * </p>
 */
@Configuration
public class GatewayMetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> gatewayApplicationTag() {
        return registry -> registry.config().commonTags("application", "my-xhs-gateway");
    }
}
