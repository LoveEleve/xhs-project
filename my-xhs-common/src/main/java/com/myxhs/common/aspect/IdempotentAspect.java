package com.myxhs.common.aspect;

import com.myxhs.common.annotation.Idempotent;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.spel.SpELParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 幂等 AOP 切面
 * <p>
 * 原理：Redis SET NX（不存在则设置），设置成功=首次请求，设置失败=重复请求。
 * </p>
 * <p>
 * 异常处理策略（关键设计决策）：
 * - BizException（业务校验失败）：删除幂等标记，允许重试
 *   例：参数校验失败、库存不足等，业务未实际执行，重试是安全的
 * - IllegalArgumentException / IllegalStateException：删除幂等标记，允许重试
 *   例：前置条件不满足，业务未执行
 * - TimeoutException / SocketTimeoutException：不删除幂等标记
 *   例：DB 超时，业务可能已在远端执行成功，重试可能导致重复
 * - 其他未知异常：不删除幂等标记（保守策略，宁可拒绝重试也不重复执行）
 * </p>
 * <p>
 * Redis 降级策略：Redis 不可用时降级放行（保证核心业务可用），同时记录告警日志。
 * </p>
 * <p>
 * 执行顺序：@Order(100)，在分布式锁之后执行
 * </p>
 */
@Slf4j
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate stringRedisTemplate;
    /** fail-open 计数（原实现只打日志 → Redis 故障期间"幂等失效"在监控侧不可见） */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final java.util.Map<String, io.micrometer.core.instrument.Counter> failOpenCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void countFailOpen(String reason) {
        failOpenCounters.computeIfAbsent(reason, r ->
                io.micrometer.core.instrument.Counter.builder("myxhs_common_fail_open_total")
                        .tag("component", "idempotent")
                        .tag("reason", r)
                        .register(meterRegistry)).increment();
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. SpEL 解析 Key
        String key = SpELParser.parse(idempotent.key(), joinPoint);
        String redisKey = idempotent.prefix() + ":" + key;

        // 2. Redis SET NX EX（原子操作）
        Boolean success;
        try {
            success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 不可用时降级放行（保证核心业务可用），同时打点：故障期间幂等保护失效需运维可见
            log.error("[幂等] Redis不可用，降级放行, key={}", redisKey, e);
            countFailOpen("redis_unavailable");
            return joinPoint.proceed();
        }

        if (Boolean.FALSE.equals(success)) {
            log.warn("[幂等拦截] 重复请求, key={}", redisKey);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, idempotent.message());
        }

        // 3. 执行业务方法
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            // 判断是否为"可安全重试"的异常
            if (isRetryableException(e)) {
                // 业务未实际执行（校验失败等），删除幂等标记允许重试
                try {
                    stringRedisTemplate.delete(redisKey);
                } catch (Exception redisEx) {
                    log.error("[幂等] 删除幂等标记失败(Redis不可用), key={}", redisKey, redisEx);
                }
                log.info("[幂等] 可重试异常，已删除幂等标记, key={}, exception={}", redisKey, e.getClass().getSimpleName());
            } else {
                // 超时/网络异常等，业务可能已执行成功，保留幂等标记防止重复
                log.warn("[幂等] 不可重试异常，保留幂等标记, key={}, exception={}", redisKey, e.getClass().getSimpleName());
            }
            throw e;
        }
    }

    /**
     * 判断异常是否为"可安全重试"类型
     * <p>
     * 可重试：业务逻辑校验失败，业务未实际执行，重试是安全的
     * 不可重试：超时/网络异常，业务可能已在远端执行成功，重试可能导致重复
     * </p>
     */
    private boolean isRetryableException(Exception e) {
        // BizException = 业务校验失败（参数错误、库存不足等），业务未执行
        if (e instanceof BizException) {
            return true;
        }
        // 参数/状态校验异常，业务未执行
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return true;
        }
        // 超时异常 = 业务可能已执行成功，不能重试
        if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
            return false;
        }
        // 递归检查 cause（有些框架会包装异常）
        Throwable cause = e.getCause();
        if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
            return false;
        }
        // 默认保守策略：不删除幂等标记（宁可拒绝重试也不重复执行）
        return false;
    }
}
