package com.myxhs.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.gateway.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway 鉴权过滤器
 * <p>
 * 核心逻辑：
 * 1. 白名单路径直接放行（注册/登录/验证码等）
 * 2. 从 Authorization Header 提取 Bearer Token
 * 3. 解析 JWT，校验签名、过期时间、Token 类型
 * 4. 检查 Token 是否在黑名单中（注销/踢出场景）
 * 5. 鉴权通过后，注入 X-User-Id Header 到下游服务
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Token 黑名单 Redis Key 前缀
     * <p>
     * 【同步要求】此值必须与 my-xhs-common 的 RedisKeyConstants.USER_TOKEN_BLACKLIST 保持一致。
     * 当前值 = "myxhs:user:token:blacklist:"。
     * Gateway 使用 WebFlux（响应式），无法引用 common 模块（Servlet），因此只能硬编码。
     * 如果 RedisKeyConstants.USER_TOKEN_BLACKLIST 发生变更，此处必须同步修改。
     * </p>
     */
    private static final String TOKEN_BLACKLIST_PREFIX = "myxhs:user:token:blacklist:";

    /** Authorization Header 前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 注入到下游的用户 ID Header */
    private static final String USER_ID_HEADER = "X-User-Id";

    /** 注入到下游的用户角色 Header（Gateway 集成 2026-08-17：从 JWT role claim 读取） */
    private static final String USER_ROLE_HEADER = "X-User-Role";

    /** 链路追踪 TraceId Header */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** Ant 路径匹配器 */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // TraceId 已由 RequestLogFilter（order=100）注入到请求 Header 中，
        // 此处直接读取即可，无需重复生成
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 1. 白名单路径直接放行
        if (isWhiteListed(path)) {
            log.info("[Gateway] 白名单放行, path={}, method={}, traceId={}", path, method, traceId);
            return chain.filter(exchange);
        }

        // 2. 提取 Authorization Header
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.info("[Gateway] 鉴权失败, 缺少 Authorization Header, path={}", path);
            return unauthorized(exchange, "缺少认证信息");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // 3. 解析 JWT
        Claims claims;
        try {
            claims = parseToken(token);
        } catch (JwtException e) {
            log.info("[Gateway] 鉴权失败, Token 解析异常: {}, path={}", e.getMessage(), path);
            return unauthorized(exchange, "Token 无效或已过期");
        }

        // 4. 校验 Token 类型必须是 access
        String tokenType = claims.get("type", String.class);
        if (!"access".equals(tokenType)) {
            log.info("[Gateway] 鉴权失败, Token 类型错误: type={}, path={}", tokenType, path);
            return unauthorized(exchange, "Token 类型错误，请使用 Access Token");
        }

        // 5+6. RV33：黑名单查询（Redis）移出 Netty EventLoop（boundedElastic），通过后再注入身份放行
        final String jti = claims.getId();
        final String effectiveTraceId = traceId;
        return Mono.fromCallable(() -> isBlacklisted(jti))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // 黑名单查询异常：安全优先（fail-closed），拒绝该请求
                    log.warn("[Gateway] 黑名单查询异常，按未认证处理: {}", e.getMessage());
                    return Mono.just(Boolean.TRUE);
                })
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.info("[Gateway] 鉴权失败, Token 已被注销, jti={}, userId={}, path={}",
                                jti, claims.getSubject(), path);
                        return unauthorized(exchange, "Token 已被注销");
                    }
                    return continueChain(exchange, chain, claims, path, method, effectiveTraceId);
                });
    }

    /** 鉴权通过：注入 X-User-Id/X-User-Role/X-Trace-Id 并放行 */
    private Mono<Void> continueChain(ServerWebExchange exchange, GatewayFilterChain chain,
                                     Claims claims, String path, String method, String traceId) {
        // C-07: 使用 set() 覆盖而非 header() 追加，防止客户端伪造 X-User-Id
        final String uid = claims.getSubject();
        // RV30：sub 为空仍注入空 X-User-Id 会让下游拿到空身份，必须拒绝
        if (uid == null || uid.isBlank()) {
            log.info("[Gateway] 鉴权失败, Token 缺少 sub: path={}", path);
            return unauthorized(exchange, "Token 无效：缺少用户标识");
        }
        final String role = claims.get("role", String.class);
        log.info("[Gateway] 鉴权通过, userId={}, role={}, path={}, method={}, traceId={}", uid, role, path, method, traceId);

        final String finalTraceId = traceId;
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(h -> {
                    h.set(USER_ID_HEADER, uid);
                    h.remove(USER_ROLE_HEADER);
                    if (role != null && !role.isBlank()) {
                        h.set(USER_ROLE_HEADER, role);
                    }
                    h.set(TRACE_ID_HEADER, finalTraceId);
                })
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        // 在 RequestLogFilter 之后执行（RequestLogFilter order = HIGHEST_PRECEDENCE + 100）
        // 确保日志记录在最前面，鉴权在日志之后
        return Ordered.HIGHEST_PRECEDENCE + 1000;
    }

    /**
     * 判断路径是否在白名单中
     */
    private boolean isWhiteListed(String path) {
        for (String pattern : authProperties.getWhiteList()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** JWT 签名密钥（启动时初始化，线程安全） */
    private volatile SecretKey secretKey;

    /**
     * 初始化 JWT 签名密钥
     * <p>
     * 使用 @PostConstruct 在 Bean 初始化阶段完成密钥创建，
     * 避免双重检查锁（DCL）在响应式多线程环境下的可见性风险。
     * </p>
     */
    @jakarta.annotation.PostConstruct
    public void initSecretKey() {
        String secret = authProperties.getSecret();
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        log.info("[Gateway] JWT签名密钥初始化完成");
    }

    /**
     * 解析 JWT Token
     * <p>
     * 使用与 my-xhs-user 相同的 HMAC-SHA256 密钥。
     * SecretKey 在 @PostConstruct 阶段初始化，保证线程安全。
     * </p>
     */
    private Claims parseToken(String token) {
        return io.jsonwebtoken.Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 检查 Token 是否在黑名单中
     * <p>
     * 用户注销登录时，Token 的 jti 会被写入 Redis 黑名单，
     * TTL 等于 Token 的剩余有效期，过期后自动清除。
     * </p>
     * <p>
     * Redis 异常处理策略：
     * - 降级为拒绝请求（Fail-Closed），防止已注销 Token 复活
     * - 相比 Fail-Open（漏放安全风险大），Fail-Closed 影响面更可控
     *   仅在 Redis ≠ 健康 + Token = 已注销 的极小概率场景下误拒
     * </p>
     */
    private boolean isBlacklisted(String jti) {
        try {
            String key = TOKEN_BLACKLIST_PREFIX + jti;
            String val = stringRedisTemplate.opsForValue().get(key);
            return val != null;
        } catch (Exception e) {
            // Redis 异常：拒绝请求（Fail-Closed），记录告警
            // 仅在短暂的 Redis 故障窗口内影响，且仅影响已注销 Token 的请求
            // 被误拒的请求前端通常会静默重试或引导重新登录，影响面可控
            log.error("[Gateway] ⚠️ Redis 黑名单查询异常，拒绝请求(安全优先), jti={}", jti, e);
            return true; // 保守策略：视为在黑名单中，拒绝
        }
    }

    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 401);
        result.put("message", message);
        result.put("data", null);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            log.error("[Gateway] JSON 序列化失败", e);
            bytes = ("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        }

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
        );
    }
}
