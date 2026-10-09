package com.myxhs.common.aspect;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * 限流 AOP 切面
 * <p>
 * 基于 Redis Lua 脚本实现滑动窗口限流（ZSet 实现）。
 * Lua 脚本保证原子性：移除过期记录 → 判断窗口内数量 → 记录本次请求。
 * </p>
 * <p>
 * Redis 降级策略：Redis 不可用时降级放行（保证核心业务可用），同时记录告警日志。
 * 降级放行意味着失去限流保护，但比全站不可用好。
 * </p>
 * <p>
 * 执行顺序：@Order(10)，最先执行（先限流再获取锁再判断幂等）
 * </p>
 */
@Slf4j
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;
    /** fail-open 计数（Redis 故障时限流整体失效，监控侧必须可见） */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final java.util.Map<String, io.micrometer.core.instrument.Counter> failOpenCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Lua 脚本：滑动窗口限流（原子操作）
     * KEYS[1] = 限流 Key
     * ARGV[1] = 窗口起始时间（当前时间 - 窗口大小）
     * ARGV[2] = 最大请求数
     * ARGV[3] = 当前时间戳（作为 ZSet 的 score）
     * ARGV[4] = 窗口大小（秒，用于设置 Key 过期时间）
     * ARGV[5] = 唯一标识（确保 ZSet member 不重复）
     */
    private static final String LUA_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            if count < tonumber(ARGV[2]) then
                redis.call('ZADD', KEYS[1], ARGV[3], ARGV[5])
                redis.call('EXPIRE', KEYS[1], ARGV[4])
                return 1
            else
                return 0
            end
            """;

    private static final DefaultRedisScript<Long> REDIS_SCRIPT = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.windowSeconds() * 1000L;
        // 使用 UUID 保证 ZSet member 绝对唯一，避免高并发下时间戳重复导致计数不准
        String uniqueId = now + ":" + UUID.randomUUID().toString().replace("-", "");

        // 执行 Lua 脚本
        Long result;
        try {
            result = stringRedisTemplate.execute(
                    REDIS_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(windowStart),
                    String.valueOf(rateLimit.maxRequests()),
                    String.valueOf(now),
                    String.valueOf(rateLimit.windowSeconds()),
                    uniqueId
            );
        } catch (Exception e) {
            // Redis 不可用时降级放行（保证核心业务可用）
            log.error("[限流] Redis不可用，降级放行, key={}", key, e);
            failOpenCounters.computeIfAbsent("redis_unavailable", r ->
                    io.micrometer.core.instrument.Counter.builder("myxhs_common_fail_open_total")
                            .tag("component", "rate_limit").tag("reason", r)
                            .register(meterRegistry)).increment();
            return joinPoint.proceed();
        }

        if (result == null || result == 0) {
            log.warn("[限流拦截] key={}, maxRequests={}/{}s", key, rateLimit.maxRequests(), rateLimit.windowSeconds());
            throw new BizException(ResultCode.RATE_LIMIT_REJECT, rateLimit.message());
        }

        return joinPoint.proceed();
    }

    /**
     * 构建限流 Key
     * 格式：{prefix}:{className}:{methodName}[:userId]
     */
    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        StringBuilder keyBuilder = new StringBuilder()
                .append(rateLimit.prefix())
                .append(":")
                .append(className)
                .append(":")
                .append(methodName);

        // 按用户限流：从 Header 中获取 X-User-Id
        if (rateLimit.perUser()) {
            String userId = getUserIdFromRequest();
            if (userId != null && !userId.isEmpty()) {
                keyBuilder.append(":").append(userId);
            } else {
                // 未登录用户按 IP 限流
                String ip = getClientIp();
                keyBuilder.append(":ip:").append(ip);
            }
        }

        return keyBuilder.toString();
    }

    /**
     * 从请求 Header 中获取用户 ID（Gateway 注入的 X-User-Id）
     */
    private String getUserIdFromRequest() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest().getHeader("X-User-Id");
            }
        } catch (Exception e) {
            // 非 Web 环境（如 MQ Consumer）忽略
        }
        return null;
    }

    /**
     * 获取客户端 IP（限流 key 用）
     * <p>
     * 安全策略（可信来源口径）：
     * 1. X-Real-IP —— 只由 gateway 写入且覆盖客户端值，首选；
     * 2. X-Forwarded-For 最后一段 —— gateway 追加的真实连接 IP 在末尾（伪造段只在前面）；
     * 3. remoteAddr 降级。
     * 历史问题：原实现只读 X-Real-IP，而 gateway 从不写它 → 恒降级为 remoteAddr（网关 IP），
     * 所有匿名用户共用一个限流桶。
     * </p>
     */
    private String getClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                // 1. gateway 单值注入，客户端不可伪造
                String ip = request.getHeader("X-Real-IP");
                if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
                // 2. XFF 取最后一段（gateway 追加的真实连接 IP 在末尾）
                String xff = request.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isEmpty()) {
                    String[] parts = xff.split(",");
                    for (int i = parts.length - 1; i >= 0; i--) {
                        String part = parts[i].trim();
                        if (!part.isEmpty()) {
                            return part;
                        }
                    }
                }
                // 3. 降级：连接 IP（开发/测试环境）
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            // 非 Web 环境忽略
        }
        return "unknown";
    }
}
