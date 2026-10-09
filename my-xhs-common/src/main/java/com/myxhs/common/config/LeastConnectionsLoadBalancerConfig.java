package com.myxhs.common.config;

import com.myxhs.common.loadbalancer.LeastConnectionsLoadBalancer;
import com.myxhs.common.loadbalancer.LeastConnectionsLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
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
 * 通过 {@link LoadBalancerClients#defaultConfiguration()} 注册到 {@link LoadBalancerClientFactory}，
 * 工厂会为每个 serviceId 创建独立的子 ApplicationContext，子 context 的 environment 中
 * {@code LoadBalancerClientFactory.PROPERTY_NAME}（即 {@code loadbalancer.client.name}）
 * 对应当前 serviceId。因此每个 serviceId 都会得到一个独立的 {@link LeastConnectionsLoadBalancer}
 * 实例，serviceId 字段正确。
 * </p>
 * <p>
 * 关键设计：用 {@link ConditionalOnProperty} 限制只在 {@code loadbalancer.client.name}
 * 存在时创建 Bean。default context（{@code @ComponentScan} 扫描的本 Config）中 name 为 null，
 * Bean 不会被创建；只在 LoadBalancerClientFactory 的子 context 中（name=serviceId）创建。
 * 这样既支持 default context 通过 {@code @ComponentScan} 扫到本 Config（用于触发
 * {@link LoadBalancerClients} 注册），又避免在 default context 创建 serviceId=null 的孤儿 Bean。
 * </p>
 * <p>
 * 注意：{@link LeastConnectionsLoadBalancer#markRequestStart(ServiceInstance)} 和
 * {@link LeastConnectionsLoadBalancer#markRequestEnd(ServiceInstance)} 需要在 Feign 的
 * Decoder/ErrorDecoder 或服务调用方 AOP 切面中手动调用，否则活跃请求数始终为 0，
 * 算法退化为基于权重的选择。
 * </p>
 */
@Configuration
@LoadBalancerClients(defaultConfiguration = LeastConnectionsLoadBalancerConfig.class)
@ConditionalOnClass(name = "org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer")
public class LeastConnectionsLoadBalancerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")
    public ReactorLoadBalancer<ServiceInstance> leastConnectionsLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory factory) {
        // @ConditionalOnProperty 已保证 name 不为 null（仅在 LoadBalancerClientFactory 子 context 创建）
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new LeastConnectionsLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }

    /**
     * 请求级埋点：把"调用开始/结束"回填给负载均衡器的活跃请求数
     * <p>
     * 与负载均衡器同处 LoadBalancerClientFactory 子 context，Spring Cloud LoadBalancer
     * 在每次服务调用时自动回调（LoadBalancerLifecycle）。
     * </p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")
    public LoadBalancerLifecycle<Object, Object, ServiceInstance> leastConnectionsLifecycle(
            LeastConnectionsLoadBalancer leastConnectionsLoadBalancer) {
        return new LeastConnectionsLifecycle(leastConnectionsLoadBalancer);
    }
}

