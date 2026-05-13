package com.myxhs.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解
 * <p>
 * 基于 Redis Lua 脚本实现滑动窗口限流。
 * 支持全局限流和按用户限流两种模式。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * // 全局限流：1秒内最多100次
 * &#64;RateLimit(windowSeconds = 1, maxRequests = 100)
 * public void globalApi() { ... }
 *
 * // 按用户限流：每个用户1秒内最多10次
 * &#64;RateLimit(windowSeconds = 1, maxRequests = 10, perUser = true)
 * public void userApi() { ... }
 * </pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 时间窗口（秒），默认 1 秒
     */
    int windowSeconds() default 1;

    /**
     * 窗口内最大请求数，默认 100
     */
    int maxRequests() default 100;

    /**
     * 是否按用户限流（从 X-User-Id Header 获取用户标识）
     */
    boolean perUser() default false;

    /**
     * Key 前缀，默认 "ratelimit"
     */
    String prefix() default "ratelimit";

    /**
     * 限流提示
     */
    String message() default "请求过于频繁，请稍后重试";
}
