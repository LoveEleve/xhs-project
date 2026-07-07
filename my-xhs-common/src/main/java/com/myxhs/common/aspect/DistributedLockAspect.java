package com.myxhs.common.aspect;

import com.myxhs.common.annotation.DistributedLock;
import com.myxhs.common.annotation.DistributedLock.LockType;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁 AOP 切面
 * <p>
 * 基于 Redisson，支持互斥锁（MUTEX）、读写锁（READ/WRITE）和公平锁（FAIR）。
 * Watchdog 原理：leaseTime=-1 时启用，默认锁 30 秒，每 10 秒（leaseTime/3）自动续期。
 * </p>
 * <p>
 * Redis 降级策略：Redis 不可用时降级放行（保证核心业务可用），同时记录告警日志。
 * 注意：降级放行意味着失去分布式锁保护，可能出现并发问题，但比全站不可用好。
 * </p>
 * <p>
 * 执行顺序：@Order(50)，在幂等之前执行（先获取锁再判断幂等）
 * </p>
 */
@Slf4j
@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockKey = getLockKey(joinPoint, distributedLock);
        LockType lockType = distributedLock.lockType();
        long waitTime = distributedLock.waitTime();
        long leaseTime = distributedLock.leaseTime();
        TimeUnit timeUnit = distributedLock.timeUnit();

        RLock lock = getLock(lockKey, lockType);
        boolean locked = false;

        try {
            if (leaseTime == -1) {
                locked = lock.tryLock(waitTime, timeUnit);
            } else {
                locked = lock.tryLock(waitTime, leaseTime, timeUnit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "获取锁被中断");
        } catch (Exception e) {
            // Redis 连接异常，降级放行
            log.error("[DistributedLock] Redis不可用，降级放行: key={}, type={}", lockKey, lockType, e);
            try {
                return joinPoint.proceed();
            } catch (Exception ex) {
                throw ex;
            }
        }

        if (!locked) {
            log.warn("[DistributedLock] 获取锁失败: key={}, type={}, waitTime={}", lockKey, lockType, waitTime);
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, distributedLock.message());
        }

        try {
            log.debug("[DistributedLock] 获取锁成功: key={}, type={}", lockKey, lockType);
            return joinPoint.proceed();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                    log.debug("[DistributedLock] 释放锁: key={}, type={}", lockKey, lockType);
                } catch (Exception e) {
                    log.warn("[DistributedLock] 释放锁失败，由 Watchdog 兜底: key={}", lockKey, e);
                }
            }
        }
    }

    /**
     * 根据锁类型获取对应的 RLock
     */
    private RLock getLock(String lockKey, LockType lockType) {
        switch (lockType) {
            case READ:
                RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockKey);
                return rwLock.readLock();
            case WRITE:
                RReadWriteLock rwLock2 = redissonClient.getReadWriteLock(lockKey);
                return rwLock2.writeLock();
            case FAIR:
                return redissonClient.getFairLock(lockKey);
            case MUTEX:
            default:
                return redissonClient.getLock(lockKey);
        }
    }

    private String getLockKey(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();
        StandardEvaluationContext context = new StandardEvaluationContext();

        String[] paramNames = signature.getParameterNames();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        String key = distributedLock.prefix() + ":" + parser.parseExpression(distributedLock.key()).getValue(context, String.class);
        return key;
    }
}
