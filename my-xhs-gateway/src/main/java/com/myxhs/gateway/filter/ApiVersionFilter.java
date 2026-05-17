package com.myxhs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API 版本路由过滤器
 * <p>
 * 职责：根据请求中的 X-Api-Version Header，将流量路由到对应版本的实例。
 * <p>
 * API 版本路由场景：
 * 1. 接口升级：v1 和 v2 实例并存，客户端通过 Header 选择版本
 * 2. A/B 测试：不同版本返回不同的业务逻辑
 * 3. 渐进式迁移：逐步将流量从 v1 迁移到 v2
 * <p>
 * 实现原理：
 * - 与灰度路由类似，通过 Nacos 实例元数据中的 api-version 标记版本
 * - v1 实例注册时元数据 api-version=v1
 * - v2 实例注册时元数据 api-version=v2
 * - 网关根据 X-Api-Version Header 过滤实例
 * <p>
 * 版本匹配策略：
 * - X-Api-Version=v1 → 只路由到 api-version=v1 的实例
 * - X-Api-Version=v2 → 只路由到 api-version=v2 的实例
 * - 未指定 X-Api-Version → 路由到默认版本（v1）实例
 * - 指定了不存在的版本 → 降级到默认版本（v1），避免 503
 * <p>
 * 当前阶段：
 * - Filter 负责 Header 解析 + 日志记录 + 将版本标记存入 Exchange 属性
 * - 实际的实例过滤由自定义 LoadBalancer 完成（与灰度路由共用机制）
 * - 后续实现 VersionLoadBalancer 时，会从 exchange.getAttribute("apiVersion") 读取
 * <p>
 * 设计决策：
 * - 版本号格式采用 v1/v2/v3...（简单递增），而非语义化版本号（v1.2.3）
 *   原因：API 版本路由是粗粒度的（整个服务级别），不需要细粒度的补丁版本
 * - 版本不匹配时降级而非拒绝，保证可用性优先
 */
@Slf4j
@Component
public class ApiVersionFilter implements GlobalFilter, Ordered {

    private static final String API_VERSION_HEADER = "X-Api-Version";
    private static final String DEFAULT_VERSION = "v1";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String apiVersion = request.getHeaders().getFirst(API_VERSION_HEADER);

        // 未指定版本时使用默认版本 v1，不需要额外处理
        if (!StringUtils.hasText(apiVersion)) {
            apiVersion = DEFAULT_VERSION;
        }

        // 非 v1 版本时记录日志（v1 是默认版本，不需要额外日志）
        if (!DEFAULT_VERSION.equals(apiVersion)) {
            String path = request.getURI().getPath();
            String traceId = request.getHeaders().getFirst("X-Trace-Id");
            log.info("[Gateway-版本] API版本路由, apiVersion={}, path={}, traceId={}",
                    apiVersion, path, traceId);
        }

        // 将 apiVersion 注入到 Gateway 的属性中，供自定义 LoadBalancer 读取
        exchange.getAttributes().put("apiVersion", apiVersion);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 在灰度路由之后执行
        return Ordered.HIGHEST_PRECEDENCE + 3100;
    }
}
