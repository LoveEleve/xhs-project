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

    /** HMAC 签名密钥（通过配置注入） */
    private final String hmacSecretKey;

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

    /** nonce 去重 Lua 脚本：SET NX EX 原子操作 */
    private static final DefaultRedisScript<Boolean> NONCE_SET_SCRIPT;

    static {
        NONCE_SET_SCRIPT = new DefaultRedisScript<>();
        NONCE_SET_SCRIPT.setScriptText(
                "if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then return true else return false end"
        );
        NONCE_SET_SCRIPT.setResultType(Boolean.class);
    }

    public HmacSignatureFilter(StringRedisTemplate stringRedisTemplate,
                               com.myxhs.gateway.config.AuthProperties authProperties,
                               ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.hmacSecretKey = authProperties.getHmacSecret();
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. HMAC 白名单路径跳过签名校验（公开接口不需要签名）
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
            return forbidden(exchange, "签名校验失败：非公开接口必须携带 X-Timestamp、X-Nonce、X-Signature");
        }

        // 3. 校验 timestamp 是否在有效期内
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.info("[Gateway-HMAC] 签名校验失败, timestamp格式错误, path={}", path);
            return forbidden(exchange, "签名校验失败：X-Timestamp 格式错误");
        }

        long currentTime = System.currentTimeMillis();
        if (Math.abs(currentTime - requestTime) > TIMESTAMP_TOLERANCE_MS) {
            log.info("[Gateway-HMAC] 签名校验失败, 请求已过期, requestTime={}, currentTime={}, path={}",
                    requestTime, currentTime, path);
            return forbidden(exchange, "签名校验失败：请求已过期");
        }

        // 4. 校验 nonce 防重放（Redis SET NX 原子操作）
        try {
            String nonceKey = NONCE_KEY_PREFIX + nonce;
            Boolean isNew = stringRedisTemplate.execute(
                    NONCE_SET_SCRIPT,
                    Collections.singletonList(nonceKey),
                    String.valueOf(TimeUnit.MINUTES.toSeconds(5))
            );
            if (Boolean.FALSE.equals(isNew)) {
                log.info("[Gateway-HMAC] 签名校验失败, nonce重复, nonce={}, path={}", nonce, path);
                return forbidden(exchange, "签名校验失败：重复请求");
            }
        } catch (Exception e) {
            // Redis 异常降级：放行请求（签名校验是安全增强，不能因 Redis 故障阻断正常流量）
            log.error("[Gateway-HMAC] Redis nonce去重异常, nonce={}, path={}", nonce, path, e);
        }

        // 5. 重新计算 HMAC-SHA256 签名
        String method = request.getMethod().name();
        String signStr = method + path + timestamp + nonce;
        String expectedSignature = hmacSha256(signStr, hmacSecretKey);

        if (expectedSignature == null || !MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            log.debug("[Gateway-HMAC] 签名校验失败, 签名不匹配, method={}, path={}, timestamp={}, nonce={}",
                    method, path, timestamp, nonce);
            return forbidden(exchange, "签名校验失败：签名不匹配");
        }

        log.debug("[Gateway-HMAC] 签名校验通过, path={}, nonce={}", path, nonce);
        return chain.filter(exchange);
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
