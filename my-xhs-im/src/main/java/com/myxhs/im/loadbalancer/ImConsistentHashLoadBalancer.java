package com.myxhs.im.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * IM 一致性 Hash 负载均衡器
 * <p>
 * 场景：WebSocket 私信需要同一 userId 始终路由到同一 IM 实例，
 * 保证用户会话在单个节点上，避免消息跨节点转发。
 * </p>
 * <p>
 * 实现：
 * - TreeMap 虚拟节点（150 virtual nodes per instance）
 * - 基于 userId Hash → 映射到虚拟节点 → 选择最近实例
 * - 实例上线/下线时重建 Hash 环
 * </p>
 * <p>
 * 使用方式：在 application.yml 中声明为默认 LoadBalancer
 * spring.cloud.loadbalancer.configurations=my-xhs-im-consistent-hash
 * </p>
 */
@Slf4j
@Component
@ConditionalOnBean(ServiceInstanceListSupplier.class)
public class ImConsistentHashLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ServiceInstanceListSupplier serviceInstanceListSupplier;

    /** 每个物理节点对应的虚拟节点数 */
    private static final int VIRTUAL_NODES_PER_INSTANCE = 150;

    /** 一致性 Hash 环（并发安全） */
    private volatile ConcurrentSkipListMap<Integer, ServiceInstance> hashRing =
            new ConcurrentSkipListMap<>();

    /** 【修复m13】缓存上一次实例列表的指纹，仅变化时才重建 Hash 环 */
    private volatile int cachedInstancesHash = 0;

    public ImConsistentHashLoadBalancer(ServiceInstanceListSupplier serviceInstanceListSupplier) {
        this.serviceInstanceListSupplier = serviceInstanceListSupplier;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        return serviceInstanceListSupplier.get().next()
                .map(instances -> {
                    if (instances.isEmpty()) {
                        log.warn("[IM-LB] 无可用 IM 实例");
                        return new EmptyResponse();
                    }

                    // 如果只有一个实例，直接返回
                    if (instances.size() == 1) {
                        ServiceInstance instance = instances.get(0);
                        return new DefaultResponse(instance);
                    }

                    // 提取 userId（从 LoadBalancer 请求上下文获取）
                    Long userId = extractUserId(request);
                    if (userId == null) {
                        // 无 userId → 轮询兜底
                        int idx = (int) (System.currentTimeMillis() % instances.size());
                        return new DefaultResponse(instances.get(idx));
                    }

                    // 重建或创建 Hash 环
                    rebuildRing(instances);

                    // 一致性 Hash 查找
                    ServiceInstance selected = findInstance(userId);
                    log.debug("[IM-LB] userId={} → instance={}", userId,
                            selected != null ? selected.getUri() : "unknown");

                    return new DefaultResponse(selected != null ? selected : instances.get(0));
                });
    }

    /**
     * 从请求上下文中提取 userId（兜底：无法提取时走轮询）
     */
    private Long extractUserId(Request request) {
        // Spring Cloud Hoxton+ 使用 DefaultRequestContext，此处简化处理
        // 实际生产环境可通过 Feign RequestInterceptor 在 Header 中注入 userId
        try {
            Object context = request.getContext();
            if (context != null) {
                // 反射获取 clientRequest（兼容不同 Spring Cloud 版本）
                var method = context.getClass().getMethod("getClientRequest");
                Object clientRequest = method.invoke(context);
                var headersMethod = clientRequest.getClass().getMethod("getHeaders");
                Object headers = headersMethod.invoke(clientRequest);
                var getFirstMethod = headers.getClass().getMethod("getFirst", String.class);
                Object userIdObj = getFirstMethod.invoke(headers, "X-User-Id");
                if (userIdObj != null) {
                    return Long.parseLong(userIdObj.toString());
                }
            }
        } catch (Exception e) {
            // 兼容性兜底：无法提取 userId 时走轮询
        }
        return null;
    }

    /**
     * 重建一致性 Hash 环
     * <p>
     * 每个实例生成 VIRTUAL_NODES_PER_INSTANCE 个虚拟节点，
     * 均匀分布在环上，减少数据倾斜。
     * </p>
     */
    private void rebuildRing(List<ServiceInstance> instances) {
        // 【修复m13】计算实例列表指纹，仅变化时才重建（避免每次请求都重建）
        int newHash = instances.stream()
                .map(i -> i.getHost() + ":" + i.getPort())
                .sorted()
                .toList()
                .hashCode();

        if (newHash == cachedInstancesHash && !hashRing.isEmpty()) {
            return; // 实例列表未变化，复用缓存的 Hash 环
        }

        ConcurrentSkipListMap<Integer, ServiceInstance> ring = new ConcurrentSkipListMap<>();

        for (ServiceInstance instance : instances) {
            String instanceKey = instance.getHost() + ":" + instance.getPort();
            for (int i = 0; i < VIRTUAL_NODES_PER_INSTANCE; i++) {
                String virtualNode = instanceKey + "#" + i;
                int hash = hash(virtualNode);
                ring.put(hash, instance);
            }
        }

        this.hashRing = ring;
        this.cachedInstancesHash = newHash;
        log.info("[IM-LB] Hash环重建完成: instances={}, virtualNodes={}",
                instances.size(), ring.size());
    }

    /**
     * 基于 userId Hash 查找最近的实例
     */
    private ServiceInstance findInstance(Long userId) {
        if (hashRing.isEmpty()) return null;

        int hash = hash(String.valueOf(userId));

        // 查找第一个 >= hash 的 entry
        Map.Entry<Integer, ServiceInstance> entry = hashRing.ceilingEntry(hash);
        if (entry == null) {
            // 环状查找：回到最小的 key
            entry = hashRing.firstEntry();
        }

        return entry != null ? entry.getValue() : null;
    }

    /**
     * FNV-1a 哈希（快速、均匀分布）
     */
    private int hash(String key) {
        int hash = 0x811c9dc5;
        for (char c : key.toCharArray()) {
            hash ^= c;
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff; // 确保正数
    }
}
