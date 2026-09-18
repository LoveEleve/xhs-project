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
        String zone = System.getProperty("myxhs.current.availability.zone", ZonePreferenceFilter.DEFAULT_ZONE);
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
