package com.myxhs.common.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.NoopServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少活跃请求数 + 预热负载均衡器
 * 
 * 替代 Spring Cloud LoadBalancer 默认的轮询策略。
 * 
 * 核心思想：
 * 1. 最少活跃请求数：优先选择当前正在处理的请求数最少的实例
 * 2. 预热机制：新启动的实例权重逐步爬升，避免冷启动被大量流量打垮
 * 3. 权重衰减：响应慢的实例权重降低
 * 
 * 为什么不用轮询：
 * RoundRobin 不考虑实例的实时负载。一个实例 CPU 90% 了还在轮询给它发请求。
 * Least Connections 确保流量总是流向"最空闲"的实例。
 * 
 * Spring Cloud LoadBalancer 调用链：
 * ReactorLoadBalancerExchangeFilterFunction.filter()
 *   → ReactorServiceInstanceLoadBalancer.choose()  ← 我们的扩展点
 * 
 * 重要：markRequestStart/markRequestEnd 的调用方式
 * 当前项目中 Feign 的 RequestInterceptor 在请求构建阶段调用，不适合标记请求开始/结束。
 * 如需精确的活跃请求数追踪，有以下几种方案：
 * - 方案A：在 Feign Decoder.decode() 和 ErrorDecoder.decode() 中调用 markRequestStart/markRequestEnd
 * - 方案B：使用 AOP 切面拦截 @FeignClient 方法调用
 * - 方案C：通过 Reactor Hooks 在每个 Mono/Flux 订阅和完成时调用
 * 
 * 如果不调用 markRequestStart/markRequestEnd，所有实例的 activeRequests 始终为 0，
 * 算法退化为基于权重（weight + 预热系数）的选择，仍然优于轮询。
 * 
 * 注册方式：通过 {@link com.myxhs.common.config.LeastConnectionsLoadBalancerConfig} 注册为 Bean，
 * Spring Cloud LoadBalancer 会自动发现并使用它。
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final String serviceId;

    /**
     * 每个实例的活跃请求数
     * key: instanceId (如 my-xhs-inventory:19009)
     * value: 当前活跃请求数
     */
    private final ConcurrentHashMap<String, AtomicInteger> activeRequests = new ConcurrentHashMap<>();

    /**
     * 预热时间（毫秒）
     * 新实例启动后 60 秒内处于预热期，权重逐步爬升
     */
    private static final long WARM_UP_MS = 60_000;

    /**
     * 预热期初始权重百分比（1%）
     */
    private static final int WARM_UP_INIT_WEIGHT_PERCENT = 1;

    public LeastConnectionsLoadBalancer(
            ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
            String serviceId) {
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.serviceId = serviceId;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = serviceInstanceListSupplierProvider
                .getIfAvailable(NoopServiceInstanceListSupplier::new);

        return supplier.get(request).next().map(instances -> {
            if (instances.isEmpty()) {
                log.warn("[LoadBalancer] 无可用实例: serviceId={}", serviceId);
                return new EmptyResponse();
            }
            return new DefaultResponse(selectInstance(instances));
        });
    }

    /**
     * 选择最优实例
     * 
     * 算法：
     * 1. 过滤不健康的实例（预热期低权重实例不算不健康，只是权重低）
     * 2. 计算每个实例的权重 = 基础权重 × 预热系数
     * 3. 选择活跃请求数 / 权重 最小的实例
     */
    private ServiceInstance selectInstance(List<ServiceInstance> instances) {
        // 收集并列最优（ratio 最小）的候选，避免平局时恒定选列表中第一个实例
        List<ServiceInstance> candidates = new ArrayList<>();
        double bestRatio = Double.MAX_VALUE;
        final double eps = 1e-9;

        for (ServiceInstance instance : instances) {
            int active = getActiveCount(instance);
            int weight = calculateWeight(instance);
            double ratio = (double) active / weight;

            if (ratio < bestRatio - eps) {
                bestRatio = ratio;
                candidates.clear();
                candidates.add(instance);
            } else if (ratio < bestRatio + eps) {
                candidates.add(instance);
            }
        }

        ServiceInstance best;
        if (candidates.isEmpty()) {
            best = instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        } else {
            best = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }

        log.debug("[LoadBalancer] 选择实例: serviceId={}, instanceId={}, activeRequests={}, ratio={}",
                serviceId, best.getInstanceId(), getActiveCount(best), bestRatio);
        return best;
    }

    /**
     * 计算实例权重
     * 
     * 权重 = 基础权重 × 预热系数
     * - 基础权重：从 Nacos metadata 读取 weight，默认为 10
     * - 预热系数：启动 60 秒内从 1% 线性爬升到 100%
     */
    private int calculateWeight(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        int baseWeight = 10;
        if (metadata.containsKey("weight")) {
            try {
                baseWeight = Integer.parseInt(metadata.get("weight"));
            } catch (NumberFormatException ignored) {}
        }

        // 预热系数
        double warmUpFactor = 1.0;
        if (metadata.containsKey("startupTime")) {
            try {
                long startupTime = Long.parseLong(metadata.get("startupTime"));
                long uptime = System.currentTimeMillis() - startupTime;
                if (uptime < WARM_UP_MS) {
                    // 线性爬升：1% → 100%
                    warmUpFactor = WARM_UP_INIT_WEIGHT_PERCENT / 100.0
                            + (1.0 - WARM_UP_INIT_WEIGHT_PERCENT / 100.0) * (uptime / (double) WARM_UP_MS);
                }
            } catch (NumberFormatException ignored) {}
        }

        return (int) Math.max(1, baseWeight * warmUpFactor);
    }

    /**
     * 获取实例当前活跃请求数
     */
    private int getActiveCount(ServiceInstance instance) {
        String key = instance.getInstanceId() != null ? instance.getInstanceId() : instance.getHost() + ":" + instance.getPort();
        return activeRequests.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
    }

    /**
     * 标记请求开始（在 Feign 拦截器中调用）
     */
    public void markRequestStart(ServiceInstance instance) {
        String key = instance.getInstanceId() != null ? instance.getInstanceId() : instance.getHost() + ":" + instance.getPort();
        activeRequests.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 标记请求结束（在 Feign 拦截器中调用）
     * <p>
     * 计数下限为 0：生命周期回调理论上成对出现，但重试/异常路径若出现多余回调，
     * 负数会让该实例永远看起来"最空闲"而被持续选中，这里做防御性收敛。
     * </p>
     */
    public void markRequestEnd(ServiceInstance instance) {
        String key = instance.getInstanceId() != null ? instance.getInstanceId() : instance.getHost() + ":" + instance.getPort();
        AtomicInteger counter = activeRequests.get(key);
        if (counter != null) {
            counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
    }
}
