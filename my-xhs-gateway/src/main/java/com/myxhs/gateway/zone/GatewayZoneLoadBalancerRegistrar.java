package com.myxhs.gateway.zone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Configuration;

/**
 * 网关 Zone 优先负载均衡注册（default context 触发，仅开关开启时注册到各 LB 子 context）。
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = GatewayZoneLoadBalancerConfiguration.class)
@ConditionalOnClass(ServiceInstanceListSupplier.class)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.preference", name = "enabled", havingValue = "true")
public class GatewayZoneLoadBalancerRegistrar {
}
