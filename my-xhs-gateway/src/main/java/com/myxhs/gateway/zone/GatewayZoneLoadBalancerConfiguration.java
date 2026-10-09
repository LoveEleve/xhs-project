package com.myxhs.gateway.zone;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 网关 Zone 优先负载均衡配置（LoadBalancer 子 context 内生效）。
 * <p>Bean 仅在子 context 创建（{@code loadbalancer.client.name} 存在），避免 default context 孤儿 Bean。</p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class GatewayZoneLoadBalancerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")
    @ConditionalOnMissingBean
    public ZonePreferenceFilter gatewayZonePreferenceFilter(Environment environment,
                                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 取值链修正：原实现只读 JVM -D 系统属性，而 gateway 不依赖 common（无 ZoneEnvironmentPostProcessor），
        // 任何脚本/容器只设 MYXHS_ZONE 时该属性从未被设置 → zone 恒为 defaultZone，Zone 优先静默失效。
        // 现口径：Spring Environment（yml/env 宽松绑定）→ JVM -D → MYXHS_ZONE 环境变量 → defaultZone。
        String zone = environment.getProperty("myxhs.current.availability.zone");
        if (zone == null || zone.isBlank()) {
            zone = System.getProperty("myxhs.current.availability.zone");
        }
        if (zone == null || zone.isBlank()) {
            zone = System.getenv("MYXHS_ZONE");
        }
        if (zone == null || zone.isBlank()) {
            zone = ZonePreferenceFilter.DEFAULT_ZONE;
        }
        int minAvailable = environment.getProperty(
                "myxhs.availability.zone.preference.upstream.same-zone-min-available", Integer.class, 1);
        return new ZonePreferenceFilter(zone, minAvailable, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")
    @ConditionalOnMissingBean(name = "optimizedZonePreferenceServiceInstanceListSupplier")
    public ServiceInstanceListSupplier optimizedZonePreferenceServiceInstanceListSupplier(
            ConfigurableApplicationContext context,
            ZonePreferenceFilter gatewayZonePreferenceFilter) {
        return ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .withCaching()
                .with((ctx, delegate) ->
                        new ZonePreferenceServiceInstanceListSupplier(delegate, gatewayZonePreferenceFilter))
                .build(context);
    }
}
