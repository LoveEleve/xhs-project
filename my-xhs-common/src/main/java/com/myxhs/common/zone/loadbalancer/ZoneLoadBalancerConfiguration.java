package com.myxhs.common.zone.loadbalancer;

import com.myxhs.common.zone.ZoneContext;
import com.myxhs.common.zone.ZonePreferenceFilter;
import com.myxhs.common.zone.metrics.ZoneRouteMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Zone 优先 LoadBalancer 配置（LoadBalancer 子 context 内生效）。
 * <p>
 * 由 {@code com.myxhs.common.config.ZoneLoadBalancerConfig} 通过
 * {@code @LoadBalancerClients(defaultConfiguration = ...)} 注册到每个 serviceId 的 LB 子 context；
 * 子 context 可见 default context 的 {@link ZoneContext} 与 {@link MeterRegistry} Bean。
 * </p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ServiceInstanceListSupplier.class)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.preference", name = "enabled", havingValue = "true")
public class ZoneLoadBalancerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter(ZoneContext zoneContext,
                                                                      ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        ZoneRouteMetrics metrics = (registry == null) ? null : new ZoneRouteMetrics(registry, zoneContext);
        return new ZonePreferenceFilter<>(zoneContext, ServiceInstanceZoneResolver.INSTANCE, metrics);
    }

    @Bean
    @ConditionalOnBean(DiscoveryClient.class)
    @ConditionalOnMissingBean(name = "optimizedZonePreferenceServiceInstanceListSupplier")
    public ServiceInstanceListSupplier optimizedZonePreferenceServiceInstanceListSupplier(
            ConfigurableApplicationContext context,
            ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter) {
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withCaching()
                .with((ctx, delegate) ->
                        new ZonePreferenceServiceInstanceListSupplier(delegate, zonePreferenceFilter))
                .build(context);
    }
}
