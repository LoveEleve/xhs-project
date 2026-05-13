package com.myxhs.common.aspect;

import com.myxhs.common.annotation.DistributedLock;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.spel.SpELParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁 AOP 切面
 * <p>
 * 基于 Redisson RLock，支持 Watchdog 自动续期。
 * Watchdog 原理：leaseTime=-1 时启用，默认锁 30 秒，每 10 秒（leaseTime/3）自动续期。
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

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 1. SpEL 解析 Key
        String key = SpELParser.parse(distributedLock.key(), joinPoint);
        String lockKey = distributedLock.prefix() + ":" + key;

        // 2. 获取 Redisson RLock
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            // leaseTime=-1 时启用 Watchdog 自动续期
            if (distributedLock.leaseTime() == -1) {
                acquired = lock.tryLock(distributedLock.waitTime(), TimeUnit.SECONDS);
            } else {
                acquired = lock.tryLock(distributedLock.waitTime(),
                        distributedLock.leaseTime(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "获取锁被中断");
        }

        if (!acquired) {
            log.warn("[分布式锁] 获取锁失败, key={}, waitTime={}s", lockKey, distributedLock.waitTime());
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, distributedLock.message());
        }

        // 3. 执行业务方法，finally 释放锁
        try {
            log.debug("[分布式锁] 获取锁成功, key={}", lockKey);
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[分布式锁] 释放锁成功, key={}", lockKey);
            }
        }
    }
}
