package com.myxhs.common.config;

import com.myxhs.common.loadbalancer.LeastConnectionsLoadBalancer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 最少活跃请求数负载均衡器配置
 * <p>
 * 注册 {@link LeastConnectionsLoadBalancer} 替代 Spring Cloud LoadBalancer 默认的轮询策略。
 * </p>
 * <p>
 * 注意：{@link LeastConnectionsLoadBalancer#markRequestStart(ServiceInstance)} 和
 * {@link LeastConnectionsLoadBalancer#markRequestEnd(ServiceInstance)} 需要在 Feign 的
 * Decoder/ErrorDecoder 或服务调用方 AOP 切面中手动调用，否则活跃请求数始终为 0，
 * 算法退化为基于权重的选择。
 * </p>
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer")
public class LeastConnectionsLoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> leastConnectionsLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new LeastConnectionsLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
}
