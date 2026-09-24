package com.myxhs.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HMAC-SHA256 签名校验过滤器
 * <p>
 * 职责：
 * 1. 校验请求中携带的 HMAC-SHA256 签名，防止请求被篡改
 * 2. 校验 timestamp 防止过期请求被重放
 * 3. 校验 nonce 防止有效请求被重复提交
 * <p>
 * 签名规则：
 * - 客户端签名输入：HTTP Method + Path + Timestamp + Nonce
 * - 签名算法：HmacSHA256(secretKey, signStr)
 * - 签名输出：Base64 编码
 * - 请求需携带 3 个 Header：X-Timestamp、X-Nonce、X-Signature
 * <p>
 * 防重放机制：
 * - timestamp：请求时间戳（毫秒），超过 5 分钟的请求直接拒绝
 * - nonce：请求唯一标识（UUID），通过 Redis SETNX 保证唯一性，TTL=5min
 * <p>
 * 设计决策：
 * - 使用 Lua 脚本保证 nonce SETNX + TTL 的原子性（虽然 SETNX + EX 在 Redis 2.6.12+
 *   支持 SET NX EX 原子操作，但 RedisTemplate 调用更清晰）
 * - 白名单路径跳过签名校验（注册/登录等公开接口不需要签名）
 * - HMAC 密钥通过 application.yml 配置，与 JWT 密钥分离
 * <p>
 * 分布式考虑：
 * - nonce 去重使用 Redis 实现，多实例共享去重状态
 * - 5 分钟 TTL 自动清理，不会无限增长
 * - Redis 异常时降级为放行（宁可漏放，不可误拒，签名校验是安全增强而非核心鉴权）
 */
@Slf4j
@Component
public class HmacSignatureFilter implements GlobalFilter, Ordered {

    private final StringRedisTemplate stringRedisTemplate;

    /** HMAC 校验失败指标（与鉴权失败指标同口径，原实现只打日志） */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final java.util.Map<String, io.micrometer.core.instrument.Counter> hmacFailureCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** HMAC 白名单路径配置 */
    private final com.myxhs.gateway.config.AuthProperties authProperties;

    /** Ant 路径匹配器 */
    private final org.springframework.util.AntPathMatcher pathMatcher = new org.springframework.util.AntPathMatcher();

    /** JSON 序列化 */
    private final ObjectMapper objectMapper;

    /** timestamp 有效期（毫秒），默认 5 分钟 */
    private static final long TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L;

    /** nonce Redis Key 前缀 */
    private static final String NONCE_KEY_PREFIX = "myxhs:gateway:nonce:";

    /** per-session HMAC 密钥 Redis Key 前缀（登录时写入，网关验签时读取） */
    private static final String HMAC_SECRET_KEY_PREFIX = "myxhs:user:hmac:secret:";

    /** nonce 去重 Lua 脚本：SET NX EX 原子操作 */
    private static final DefaultRedisScript<Boolean> NONCE_SET_SCRIPT;

    static {
        NONCE_SET_SCRIPT = new DefaultRedisScript<>();
        NONCE_SET_SCRIPT.setScriptText(
                "if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then return true else return false end"
        );
        NONCE_SET_SCRIPT.setResultType(Boolean.class);
    }

    /**
     * RV34：阻塞式 Redis 读取的结果载体。
     * <p>
     * 读密钥需在 boundedElastic 线程上执行，若直接抛出异常，外层 onErrorResume 会连
     * 下游过滤链的异常一并捕获（语义被扩大）。用本载体把"Redis 异常"从流里显式带出来，
     * 保证异常处理范围与改造前完全一致。
     * </p>
     */
    private static final class SecretLookup {

        final String value;

        final Exception error;

        private SecretLookup(String value, Exception error) {
            this.value = value;
            this.error = error;
        }

        static SecretLookup ok(String value) {
            return new SecretLookup(value, null);
        }

        static SecretLookup failed(Exception error) {
            return new SecretLookup(null, error);
        }
    }

    public HmacSignatureFilter(StringRedisTemplate stringRedisTemplate,
                               com.myxhs.gateway.config.AuthProperties authProperties,
                               ObjectMapper objectMapper,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    private void countHmacFailure(String reason) {
        hmacFailureCounters.computeIfAbsent(reason, r ->
                io.micrometer.core.instrument.Counter.builder("myxhs_gateway_hmac_failures_total")
                        .tag("reason", r)
                        .register(meterRegistry)).increment();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. HMAC 默认降级为可选增强：关闭时直接跳过整条签名链路
        if (!authProperties.isHmacEnabled()) {
            return chain.filter(exchange);
        }

        // 2. HMAC 白名单路径跳过签名校验（公开接口不需要签名）
        // 判断逻辑：检查路径是否在 hmacWhiteList 中
        // - 白名单内的路径（如登录/注册/公开读接口）不需要签名，直接放行
        // - 非白名单路径必须携带完整的签名 Header（X-Timestamp + X-Nonce + X-Signature）
        // 安全原则：不能仅凭"缺少 X-Timestamp"就放行，否则攻击者可不传签名绕过校验
        if (isHmacWhiteListed(path)) {
            return chain.filter(exchange);
        }

        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");
        String signature = request.getHeaders().getFirst("X-Signature");

        // 2. 非白名单路径必须携带完整的签名 Header
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            log.info("[Gateway-HMAC] 签名校验失败, 非白名单路径缺少签名Header, path={}", path);
            countHmacFailure("missing_headers");
            return forbidden(exchange, "签名校验失败：非公开接口必须携带 X-Timestamp、X-Nonce、X-Signature");
        }

        // 3. 校验 timestamp 是否在有效期内
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.info("[Gateway-HMAC] 签名校验失败, timestamp格式错误, path={}", path);
            countHmacFailure("bad_timestamp");
            return forbidden(exchange, "签名校验失败：X-Timestamp 格式错误");
        }

        long currentTime = System.currentTimeMillis();
        if (Math.abs(currentTime - requestTime) > TIMESTAMP_TOLERANCE_MS) {
            log.info("[Gateway-HMAC] 签名校验失败, 请求已过期, requestTime={}, currentTime={}, path={}",
                    requestTime, currentTime, path);
            countHmacFailure("expired");
            return forbidden(exchange, "签名校验失败：请求已过期");
        }

        // 5. 获取 per-session HMAC 密钥（从 GatewayAuthFilter 注入的 X-User-Id 取 userId）
        // 【设计改进】不再用全局 hmacSecretKey（配置文件硬编码，前端知道=签名失效），
        // 改为登录时生成 per-session secret 存 Redis，前端从登录响应获取，gateway 从 Redis 取验签。
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId == null) {
            log.info("[Gateway-HMAC] 签名校验失败, 缺少 X-User-Id, path={}", path);
            countHmacFailure("no_user");
            return forbidden(exchange, "签名校验失败：缺少用户身份");
        }
        // 6. 组装签名串
        // T-009/010/011: 签名串 = method|path|query|ts|nonce|bodyHash（竖线分隔 + query + body 摘要）
        final String fUserId = userId;
        final String fPath = path;
        final String fNonce = nonce;
        final String fSignature = signature;
        final String fMethod = request.getMethod().name();
        String rawQuery = request.getURI().getRawQuery();
        final String fQuery = (rawQuery == null) ? "" : rawQuery;
        byte[] cachedBody = exchange.getAttribute(BodyCacheFilter.CACHED_BODY_ATTR);
        final String fBodyHash = (cachedBody != null && cachedBody.length > 0) ? sha256Hex(cachedBody) : "";
        final String signStr = fMethod + "|" + fPath + "|" + fQuery + "|"
                + timestamp + "|" + fNonce + "|" + fBodyHash;

        // RV34：本过滤器有两处阻塞式 StringRedisTemplate 调用（读 HMAC 密钥、nonce 去重），
        // 原先直接在 filter() 方法体里执行 → 会阻塞 Netty EventLoop 线程。
        // 现统一移出 EventLoop（boundedElastic），与 GatewayAuthFilter RV33（黑名单查询移出 EventLoop）同一模式。
        return Mono.fromCallable(() -> {
                    try {
                        return SecretLookup.ok(stringRedisTemplate.opsForValue()
                                .get(HMAC_SECRET_KEY_PREFIX + fUserId));
                    } catch (Exception e) {
                        return SecretLookup.failed(e);
                    }
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(lookup -> {
                    if (lookup.error != null) {
                        // P2-11: Redis 故障时拒绝请求（fail-closed），避免 500 与签名校验绕过
                        log.warn("[Gateway-HMAC] Redis 读取 HMAC 密钥异常, path={}, userId={}, err={}",
                                fPath, fUserId, lookup.error.getMessage());
                        return forbidden(exchange, "签名校验暂不可用，请稍后重试");
                    }
                    if (lookup.value == null) {
                        log.info("[Gateway-HMAC] 签名校验失败, HMAC密钥已过期, userId={}, path={}", fUserId, fPath);
                        countHmacFailure("secret_expired");
                        return forbidden(exchange, "签名校验失败：HMAC 密钥已过期，请重新登录");
                    }
                    // 统一反序列化处理：RedisTemplate 用 Jackson 序列化 String 可能多一层引号。
                    // 使用 com.fasterxml.jackson.databind.ObjectMapper 直接反序列化替代手工 strip，
                    // 避免密钥本身以引号开头/结尾时被截断。
                    String perUserSecret = lookup.value;
                    try {
                        perUserSecret = objectMapper.readValue(lookup.value, String.class);
                    } catch (Exception e) {
                        log.warn("[Gateway-HMAC] HMAC 密钥反序列化失败，使用原始值: userId={}", fUserId);
                    }

                    // 7. 重新计算 HMAC-SHA256 签名（用 per-session secret）
                    String expectedSignature = hmacSha256(signStr, perUserSecret);
                    if (expectedSignature == null || !MessageDigest.isEqual(
                            expectedSignature.getBytes(StandardCharsets.UTF_8),
                            fSignature.getBytes(StandardCharsets.UTF_8))) {
                        log.debug("[Gateway-HMAC] 签名校验失败, 签名不匹配, method={}, path={}, timestamp={}, nonce={}, userId={}",
                                fMethod, fPath, timestamp, fNonce, fUserId);
                        countHmacFailure("mismatch");
                        return forbidden(exchange, "签名校验失败：签名不匹配");
                    }

                    // 8. 验签通过后再消费 nonce（RV30：原顺序为先占 nonce 再验签，错误签名可抢占合法 nonce 造成重放误判/DoS）
                    // RV34：同样移出 Netty EventLoop（boundedElastic）
                    return Mono.fromCallable(() -> stringRedisTemplate.execute(
                                    NONCE_SET_SCRIPT,
                                    Collections.singletonList(NONCE_KEY_PREFIX + fNonce),
                                    String.valueOf(TimeUnit.MINUTES.toSeconds(5))))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                // Redis 异常降级：放行请求（签名校验是安全增强，不能因 Redis 故障阻断正常流量）
                                log.error("[Gateway-HMAC] Redis nonce去重异常, nonce={}, path={}", fNonce, fPath, e);
                                return Mono.just(Boolean.TRUE);
                            })
                            .flatMap(isNew -> {
                                if (Boolean.FALSE.equals(isNew)) {
                                    log.info("[Gateway-HMAC] 签名校验失败, nonce重复, nonce={}, path={}", fNonce, fPath);
                                    countHmacFailure("nonce_reused");
                                    return forbidden(exchange, "签名校验失败：重复请求");
                                }
                                log.debug("[Gateway-HMAC] 签名校验通过, path={}, nonce={}, userId={}",
                                        fPath, fNonce, fUserId);
                                return chain.filter(exchange);
                            });
                });
    }

    @Override
    public int getOrder() {
        // 在鉴权之后执行（AuthFilter order = HIGHEST_PRECEDENCE + 1000）
        return Ordered.HIGHEST_PRECEDENCE + 1500;
    }

    /**
     * 判断路径是否在 HMAC 签名白名单中
     */
    private boolean isHmacWhiteListed(String path) {
        for (String pattern : authProperties.getHmacWhiteList()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算 HMAC-SHA256 签名
     *
     * @param data 待签名数据
     * @param key  HMAC 密钥
     * @return Base64 编码的签名值，异常时返回 null
     */
    private String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * HMAC-SHA256 计算
     *
     * @param data 待签名数据
     * @param key  HMAC 密钥
     * @return Base64 编码的签名值，异常时返回 null
     */
    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("[Gateway-HMAC] HMAC计算异常", e);
            return null;
        }
    }

    /**
     * 返回 403 禁止访问响应
     */
    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 403);
        result.put("message", message);
        result.put("data", null);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
        );
    }
}
