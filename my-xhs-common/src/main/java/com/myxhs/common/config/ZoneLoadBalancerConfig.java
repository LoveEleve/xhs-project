package com.myxhs.common.config;

import com.myxhs.common.zone.loadbalancer.ZoneLoadBalancerConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Configuration;

/**
 * Zone 优先负载均衡注册（default context 触发）。
 * <p>
 * 与 {@link LeastConnectionsLoadBalancerConfig} 同模式：在 default context 通过
 * {@link LoadBalancerClients#defaultConfiguration()} 把 {@link ZoneLoadBalancerConfiguration}
 * 注册进每个 serviceId 的 LoadBalancer 子 context。
 * </p>
 * <p>
 * 背景（2026-09-18 实测修复）：此前直接把 Supplier Bean 放在 default context（AutoConfiguration），
 * LB 子 context 并不会采用（子 context 自己的默认 Supplier 先行生效），表现为"配置全开但路由不生效、
 * 指标为零"。必须走 defaultConfiguration 才会在子 context 内创建。
 * </p>
 * <p>
 * 仅当 {@code myxhs.availability.zone.preference.enabled=true} 时注册（ConditionalOnProperty 不满足时
 * 本配置类整体跳过，{@link LoadBalancerClients} 注册器也不会执行）。
 * </p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = ZoneLoadBalancerConfiguration.class)
@ConditionalOnClass(ServiceInstanceListSupplier.class)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.preference", name = "enabled", havingValue = "true")
public class ZoneLoadBalancerConfig {
}
