package com.myxhs.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁注解
 * <p>
 * 基于 Redisson RLock 实现，支持 Watchdog 自动续期。
 * Key 支持 SpEL 表达式，可从方法参数中动态提取。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * &#64;DistributedLock(key = "'inventory:deduct:' + #skuId", waitTime = 3, leaseTime = -1)
 * public void deductStock(Long skuId, Integer quantity) { ... }
 * </pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 锁 Key 的 SpEL 表达式
     */
    String key();

    /**
     * 等待获取锁的超时时间（秒），默认 3 秒
     */
    long waitTime() default 3;

    /**
     * 锁自动释放时间（秒）
     * -1 表示启用 Watchdog 自动续期（默认 30 秒锁，每 10 秒续期）
     */
    long leaseTime() default -1;

    /**
     * Key 前缀，默认 "lock"
     */
    String prefix() default "lock";

    /**
     * 获取锁失败提示
     */
    String message() default "操作过于频繁，请稍后重试";
}
