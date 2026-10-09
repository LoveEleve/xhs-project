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

import java.util.UUID;

/**
 * Gateway 流量染色过滤器
 * <p>
 * 在请求入口注入/透传 6 个染色标记 Header，全链路透传到下游服务。
 * 执行顺序：在 GatewayAuthFilter 之后（AuthFilter 已注入 X-User-Id）。
 * </p>
 * <p>
 * 染色策略：
 * 1. X-Trace-Id：没有则生成（已在 AuthFilter 中处理，此处兜底）
 * 2. X-Gray-Tag：客户端显式指定 → 直接使用；否则默认 stable
 * 3. X-Api-Version：客户端指定 → 透传；否则默认 v1
 * 4. X-AB-Group：客户端指定 → 透传；否则根据 userId hash 分组
 * 5. X-Pressure-Test：客户端指定 true → 标记为压测流量
 * 6. X-User-Id：已在 AuthFilter 中注入，此处不处理
 * </p>
 * <p>
 * 安全考虑：
 * - X-Pressure-Test 只允许特定来源设置（生产环境应限制为压测平台 IP）
 * - X-Gray-Tag 允许客户端覆盖（方便测试），生产环境可改为服务端决策
 * </p>
 */
@Slf4j
@Component
public class TrafficColoringFilter implements GlobalFilter, Ordered {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String GRAY_TAG_HEADER = "X-Gray-Tag";
    private static final String API_VERSION_HEADER = "X-Api-Version";
    private static final String AB_GROUP_HEADER = "X-AB-Group";
    private static final String PRESSURE_TEST_HEADER = "X-Pressure-Test";
    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. TraceId 兜底（AuthFilter 已处理，此处确保不遗漏）
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 灰度标记（客户端指定 → 直接使用；否则默认 stable）
        String grayTag = request.getHeaders().getFirst(GRAY_TAG_HEADER);

        // 3. API 版本（客户端指定 → 透传；否则默认 v1）
        String apiVersion = request.getHeaders().getFirst(API_VERSION_HEADER);
        if (!StringUtils.hasText(apiVersion)) {
            apiVersion = "v1";
        }

        // 4. AB 测试分组（客户端指定 → 透传；否则根据 userId hash 分组）
        String abGroup = request.getHeaders().getFirst(AB_GROUP_HEADER);
        if (!StringUtils.hasText(abGroup)) {
            String userId = request.getHeaders().getFirst(USER_ID_HEADER);
            abGroup = determineAbGroup(userId);
        }

        // 5. 压测标记（只有显式传 true 才标记为压测流量）
        // 【修复C3】使用 X-Forwarded-For / X-Real-IP 获取真实客户端 IP，防止经反代后拿到代理 IP
        // 防止攻击者伪造压测标记绕过限流规则
        String pressureTest = request.getHeaders().getFirst(PRESSURE_TEST_HEADER);
        boolean isPressure = false;
        if ("true".equals(pressureTest)) {
            String clientIp = getClientIp(request);
            // 仅允许压测平台 IP 设置压测标记（10.0.0.0/8 内网段）
            // 生产环境应改为具体压测平台 IP 白名单，如 "10.0.1.100"
            if (clientIp != null && clientIp.startsWith("10.")) {
                isPressure = true;
                log.info("[Gateway-染色] 压测流量标记, traceId={}, path={}, clientIp={}",
                        traceId, request.getURI().getPath(), clientIp);
            } else {
                log.warn("[Gateway-染色] 拒绝外部IP伪造压测标记, traceId={}, clientIp={}",
                        traceId, clientIp);
            }
        }

        // 6. 统一构建 mutated request（使用 headers consumer 覆盖式设置，避免追加重复值）
        final String finalTraceId = traceId;
        final String finalGrayTag = grayTag;
        final String finalApiVersion = apiVersion;
        final String finalAbGroup = abGroup;
        final String finalPressureTest = isPressure ? "true" : "false";

        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> {
                    headers.set(TRACE_ID_HEADER, finalTraceId);
                    if (StringUtils.hasText(finalGrayTag)) {
                        headers.set(GRAY_TAG_HEADER, finalGrayTag);
                    } else {
                        headers.remove(GRAY_TAG_HEADER);
                    }
                    headers.set(API_VERSION_HEADER, finalApiVersion);
                    headers.set(AB_GROUP_HEADER, finalAbGroup);
                    headers.set(PRESSURE_TEST_HEADER, finalPressureTest);
                    // 【P0-7修复】追加当前连接IP到 X-Forwarded-For，保留原始代理链，防客户端伪造绕过反作弊
                    String originalXff = headers.getFirst("X-Forwarded-For");
                    String remoteAddr = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
                    if (originalXff != null && !originalXff.isEmpty()) {
                        headers.set("X-Forwarded-For", originalXff + ", " + remoteAddr);
                    } else {
                        headers.set("X-Forwarded-For", remoteAddr);
                    }
                    // 【单一可信来源】X-Real-IP 只由网关写入并覆盖客户端值：
                    // 下游（RateLimitAspect / user 登录锁定）只认它，客户端自带的一律失效
                    headers.set("X-Real-IP", remoteAddr);
                })
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // 在 AuthFilter 之后、HMAC 签名校验之前执行
        // 这样可以根据 X-User-Id 来决定 AB 分组
        return Ordered.HIGHEST_PRECEDENCE + 1200;
    }

    /**
     * 根据 userId 确定 AB 测试分组
     * <p>
     * 策略：userId hash 取模 3，分为 A/B/C 三组。
     * 未登录用户（userId 为空）默认分到 A 组。
     * </p>
     */
    private String determineAbGroup(String userId) {
        if (!StringUtils.hasText(userId)) {
            return "A";
        }
        // 使用 (hash & 0x7FFFFFFF) 代替 Math.abs()，避免 Integer.MIN_VALUE 溢出
        int hash = (userId.hashCode() & 0x7FFFFFFF) % 3;
        return switch (hash) {
            case 0 -> "A";
            case 1 -> "B";
            default -> "C";
        };
    }

    /**
     * 获取客户端真实 IP（网关自身决策用：压测标记/灰度判定）
     * <p>
     * 【安全修正】网关是边缘节点（本部署无上游反代），直连 IP 才可信：
     * 客户端自带的 X-Forwarded-For / X-Real-IP 均可伪造——伪造 10.x 曾可骗取压测标记。
     * 因此只取 remoteAddress；若未来引入 Nginx/ALB，应改为"可信代理链校验后取最后一段"。
     * </p>
     */
    private String getClientIp(ServerHttpRequest request) {
        // 直连 IP（网关是边缘节点，无上游反代）——原实现两个分支等价，去重
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return null;
    }
}
