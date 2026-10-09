package com.myxhs.common.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;

/**
 * 最少连接负载均衡器的请求级埋点（LoadBalancerLifecycle）
 * <p>
 * Spring Cloud LoadBalancer 在服务调用的两个时点回调本类：
 * 1. 实例选出后（onStartRequest）：对选中实例的活跃请求数 +1
 * 2. 调用结束（onComplete，无论成功/失败/异常）：-1
 * </p>
 * <p>
 * 没有这层埋点时，{@link LeastConnectionsLoadBalancer} 的 activeRequests 恒为 0，
 * 算法会退化成"按权重选择"；接上之后才真正按"活跃请求数 / 权重"选实例，
 * 并按调用完成情况实时回填负载，慢实例自然被降低选中概率。
 * </p>
 * <p>
 * 覆盖路径：Feign（含重试客户端）与 LoadBalanced 的 RestTemplate/RestClient ——
 * 三者都通过 LoadBalancerUtils / BlockingLoadBalancerClient 触发同一套 Lifecycle 回调。
 * </p>
 */
@Slf4j
public class LeastConnectionsLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private final LeastConnectionsLoadBalancer loadBalancer;

    public LeastConnectionsLifecycle(LeastConnectionsLoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    @Override
    public void onStart(Request<Object> request) {
        // 此时尚未选出实例，不做标记；实例选出后在 onStartRequest 里 +1
    }

    @Override
    public void onStartRequest(Request<Object> request, Response<ServiceInstance> response) {
        if (response != null && response.hasServer()) {
            loadBalancer.markRequestStart(response.getServer());
        }
    }

    @Override
    public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
        if (completionContext == null) {
            return;
        }
        Response<ServiceInstance> response = completionContext.getLoadBalancerResponse();
        if (response != null && response.hasServer()) {
            loadBalancer.markRequestEnd(response.getServer());
        }
    }
}
