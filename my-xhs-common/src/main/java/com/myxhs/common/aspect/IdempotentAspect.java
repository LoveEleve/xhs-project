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

import java.util.concurrent.TimeUnit;

/**
 * 幂等 AOP 切面
 * <p>
 * 原理：Redis SET NX（不存在则设置），设置成功=首次请求，设置失败=重复请求。
 * 业务异常时主动删除幂等标记，允许重试。
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

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. SpEL 解析 Key
        String key = SpELParser.parse(idempotent.key(), joinPoint);
        String redisKey = idempotent.prefix() + ":" + key;

        // 2. Redis SET NX EX（原子操作）
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(success)) {
            log.warn("[幂等拦截] 重复请求, key={}", redisKey);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, idempotent.message());
        }

        // 3. 执行业务方法
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            // 业务异常时删除幂等标记，允许重试
            stringRedisTemplate.delete(redisKey);
            log.info("[幂等] 业务异常，已删除幂等标记允许重试, key={}", redisKey);
            throw e;
        }
    }
}
