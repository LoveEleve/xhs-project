package com.myxhs.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解
 * <p>
 * 基于 Redisson 实现，支持互斥锁、读写锁和公平锁。
 * Key 支持 SpEL 表达式，可从方法参数中动态提取。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * &#64;DistributedLock(key = "'inventory:deduct:' + #skuId", lockType = LockType.FAIR, waitTime = 3)
 * public void deductStock(Long skuId, Integer quantity) { ... }
 * </pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 锁类型
     */
    LockType lockType() default LockType.MUTEX;

    /**
     * 锁 Key 的 SpEL 表达式
     */
    String key();

    /**
     * 等待获取锁的超时时间，默认 3 秒
     */
    long waitTime() default 3;

    /**
     * 锁自动释放时间
     * -1 表示启用 Watchdog 自动续期（默认 30 秒锁，每 10 秒续期）
     */
    long leaseTime() default -1;

    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * Key 前缀，默认 "lock"
     */
    String prefix() default "lock";

    /**
     * 获取锁失败提示
     */
    String message() default "操作过于频繁，请稍后重试";

    enum LockType {
        /** 互斥锁（默认） */
        MUTEX,
        /** 读锁（共享锁，允许多个读并发） */
        READ,
        /** 写锁（排他锁，写时排斥所有读写） */
        WRITE,
        /** 公平锁（先到先得） */
        FAIR
    }
}
