package com.myxhs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 灰度路由过滤器
 * <p>
 * 职责：根据请求中的 X-Gray-Tag Header，将流量路由到对应灰度标记的实例。
 * <p>
 * 灰度发布流程：
 * 1. 灰度实例注册到 Nacos 时，在元数据中标记 gray-tag=gray
 * 2. 正式实例的元数据中 gray-tag=stable（或不设置，默认 stable）
 * 3. 客户端通过 X-Gray-Tag=gray Header 指定访问灰度实例
 * 4. 未指定 X-Gray-Tag 时，默认路由到 stable 实例
 * <p>
 * 实现原理：
 * Spring Cloud Gateway 的路由选择由 RoutePredicateHandlerMapping 决定，
 * 负载均衡由 ReactiveLoadBalancerClientFilter（或 LoadBalancerClientFilter）完成。
 * <p>
 * 灰度路由的实现方式有多种：
 * - 方案A：自定义 LoadBalancerPolicy（推荐）— 在 ServiceInstanceListSupplier 中过滤实例
 * - 方案B：自定义 RouteLocator — 根据 Header 动态生成路由规则
 * - 方案C：修改 GATEWAY_ROUTE_ATTR — 在 Filter 中替换 Route 的 URI
 * <p>
 * 选择方案C的原因：
 * - 最轻量，不需要额外注册 LoadBalancer 组件
 * - 对现有路由配置无侵入
 * - 适合当前"同一服务同时存在 stable/gray 实例"的场景
 * <p>
 * 方案C 实现逻辑：
 * 1. 从 Exchange 中获取已匹配的 Route
 * 2. 检查请求中的 X-Gray-Tag Header
 * 3. 如果 gray-tag=gray，在 Route URI 的 host 部分添加 gray 标记
 *    （配合自定义 LoadBalancer 的元数据过滤使用）
 * 4. 当前阶段：仅做 Header 透传 + 日志记录，实际的实例过滤由
 *    GrayLoadBalancer（需后续实现）完成
 * <p>
 * 分布式考虑：
 * - 灰度标记通过 Nacos 元数据传播，所有网关实例看到相同的实例列表
 * - 灰度比例控制由上游（如 CDN / 前端）按百分比设置 X-Gray-Tag 决定
 * - 后续可扩展为网关侧按百分比自动打标（基于用户ID hash 或随机数）
 */
@Slf4j
@Component
public class GrayRouteFilter implements GlobalFilter, Ordered {

    private static final String GRAY_TAG_HEADER = "X-Gray-Tag";

    private static final int GRAY_PERCENT = 10; // 默认 10% 流量进入灰度

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String grayTag = request.getHeaders().getFirst(GRAY_TAG_HEADER);

        // 没有显式 gray-tag 时，基于 userId Hash 自动分配灰度比例
        if (grayTag == null || grayTag.isEmpty()) {
            String userId = request.getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isEmpty()) {
                // 【修复M11】使用位运算去符号位，避免 Math.abs(Integer.MIN_VALUE) 仍为负数的溢出问题
                int hash = (userId.hashCode() & 0x7FFFFFFF) % 100;
                if (hash < GRAY_PERCENT) {
                    grayTag = "gray";
                    log.debug("[Gateway-灰度] userId Hash 命中灰度比例: userId={}, hashMod={}",
                            userId, hash);
                }
            }
        }

        // stable 或未命中灰度 → 正常路由
        if (grayTag == null || "stable".equals(grayTag)) {
            return chain.filter(exchange);
        }

        // 灰度流量 —— 记录日志并注入属性
        String path = request.getURI().getPath();
        log.info("[Gateway-灰度] 灰度流量路由, grayTag={}, path={}", grayTag, path);

        exchange.getAttributes().put("grayTag", grayTag);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 在限流之后执行
        return Ordered.HIGHEST_PRECEDENCE + 3000;
    }
}
