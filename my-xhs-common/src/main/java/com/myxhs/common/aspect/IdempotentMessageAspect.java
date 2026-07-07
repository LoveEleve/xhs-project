package com.myxhs.common.aspect;

import com.myxhs.common.annotation.IdempotentMessage;
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
import java.util.concurrent.TimeoutException;

/**
 * MQ 消息幂等 AOP 切面
 * <p>
 * 原理：Redis SET NX（不存在则设置），设置成功=首次消费，设置失败=重复消费。
 * </p>
 * <p>
 * 异常处理策略：
 * - 业务处理失败时删除幂等标记，允许 MQ 重试
 * - 超时异常不删除幂等标记（保守策略，业务可能已执行成功）
 * </p>
 * <p>
 * 执行顺序：@Order(1)，最先执行
 * </p>
 * <p>
 * 注意：由于 RocketMQ 消费者通常直接实现 RocketMQListener 接口，
 * AOP 可能无法直接拦截 onMessage 方法。
 * 建议将业务逻辑抽取为独立 Service 方法后使用此注解，
 * 或直接使用 MessageIdempotentHelper 工具类。
 * </p>
 */
@Slf4j
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class IdempotentMessageAspect {

    private final StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(idempotentMessage)")
    public Object around(ProceedingJoinPoint joinPoint, IdempotentMessage idempotentMessage) throws Throwable {
        // 1. 解析 Key
        String bizKey;
        String keyExpr = idempotentMessage.key();
        if (!keyExpr.isEmpty()) {
            bizKey = SpELParser.parse(keyExpr, joinPoint);
        } else {
            // 默认取第一个参数的 toString
            Object[] args = joinPoint.getArgs();
            if (args.length > 0 && args[0] != null) {
                bizKey = args[0].toString();
            } else {
                log.warn("[消息幂等] 无法解析业务 Key，降级放行");
                return joinPoint.proceed();
            }
        }

        if (bizKey == null || bizKey.isEmpty()) {
            log.warn("[消息幂等] 业务 Key 为空，降级放行");
            return joinPoint.proceed();
        }

        // 2. 构建 Redis Key
        String prefix = idempotentMessage.prefix();
        if (prefix.isEmpty()) {
            prefix = joinPoint.getSignature().getDeclaringType().getSimpleName()
                    + "." + joinPoint.getSignature().getName();
        }
        String redisKey = "msg:idempotent:" + prefix + ":" + bizKey;

        // 3. Redis SET NX
        Boolean success;
        try {
            success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", idempotentMessage.ttl(), idempotentMessage.timeUnit());
        } catch (Exception e) {
            log.error("[消息幂等] Redis不可用，降级放行: key={}", redisKey, e);
            return joinPoint.proceed();
        }

        if (Boolean.FALSE.equals(success)) {
            log.warn("[消息幂等] 重复消息已忽略: key={}", redisKey);
            return null;
        }

        // 4. 执行业务
        log.info("[消息幂等] 首次处理: key={}", redisKey);
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            if (isRetryableException(e)) {
                try {
                    stringRedisTemplate.delete(redisKey);
                } catch (Exception redisEx) {
                    log.error("[消息幂等] 删除幂等标记失败: key={}", redisKey, redisEx);
                }
                log.info("[消息幂等] 可重试异常，已删除幂等标记: key={}", redisKey);
            } else {
                log.warn("[消息幂等] 不可重试异常，保留幂等标记: key={}", redisKey);
            }
            throw e;
        }
    }

    private boolean isRetryableException(Throwable e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return true;
        }
        if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
            return false;
        }
        Throwable cause = e.getCause();
        if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
            return false;
        }
        return false;
    }
}
