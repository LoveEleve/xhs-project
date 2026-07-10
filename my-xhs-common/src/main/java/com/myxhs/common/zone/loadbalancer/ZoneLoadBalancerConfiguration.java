package com.myxhs.common.zone.loadbalancer;

import com.myxhs.common.zone.ZoneContext;
import com.myxhs.common.zone.ZonePreferenceFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Zone 优先 LoadBalancer 自动配置。
 * <p>
 * 当 myxhs.availability.zone.preference.enabled=true 时，
 * 自动创建 Zone 优先的 ServiceInstanceListSupplier。
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ServiceInstanceListSupplier.class)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.preference", name = "enabled", havingValue = "true")
public class ZoneLoadBalancerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter(ZoneContext zoneContext) {
        return new ZonePreferenceFilter<>(zoneContext, ServiceInstanceZoneResolver.INSTANCE);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ReactiveDiscoveryClient.class)
    @Order(193827465)
    static class ReactiveConfiguration {

        @Bean
        @ConditionalOnBean(DiscoveryClient.class)
        @ConditionalOnMissingBean(name = "optimizedZonePreferenceServiceInstanceListSupplier")
        public ServiceInstanceListSupplier optimizedZonePreferenceServiceInstanceListSupplier(
                ConfigurableApplicationContext context,
                ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter) {
            return ServiceInstanceListSupplier.builder()
                    .withDiscoveryClient()
                    .withCaching()
                    .with((ctx, delegate) ->
                            new ZonePreferenceServiceInstanceListSupplier(delegate, zonePreferenceFilter))
                    .build(context);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DiscoveryClient.class)
    @Order(193827466)
    static class BlockingConfiguration {

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
}
