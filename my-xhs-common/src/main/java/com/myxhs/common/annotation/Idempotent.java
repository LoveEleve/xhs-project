package com.myxhs.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等注解
 * <p>
 * 基于 Redis SET NX 实现，在指定时间窗口内，相同 Key 的请求只允许执行一次。
 * Key 支持 SpEL 表达式，可从方法参数中动态提取。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * &#64;Idempotent(key = "'order:create:' + #request.userId", expireSeconds = 30)
 * public OrderVO createOrder(CreateOrderRequest request) { ... }
 * </pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等 Key 的 SpEL 表达式
     */
    String key();

    /**
     * 过期时间（秒），默认 60 秒
     */
    long expireSeconds() default 60;

    /**
     * Key 前缀，默认 "idempotent"
     */
    String prefix() default "idempotent";

    /**
     * 重复请求提示信息
     */
    String message() default "请勿重复操作";
}
