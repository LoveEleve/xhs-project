package com.myxhs.common.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * MQ 消息幂等注解
 * <p>
 * 标注在 MQ 消费者方法上，基于 Redis SET NX 自动实现消息去重。
 * 适用于消息处理类（非 RocketMQListener 接口方式，而是通过 Service 方法被调用的场景）。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * &#64;IdempotentMessage(prefix = "coupon:claim", key = "#event.msgId")
 * public void handleClaim(CouponClaimEvent event) {
 *     // 业务逻辑
 * }
 * </pre>
 * </p>
 * <p>
 * 注意：由于 RocketMQ 消费者通常直接实现 RocketMQListener 接口（而非 Spring Bean 方法调用），
 * AOP 可能无法直接拦截 onMessage 方法。建议通过 MessageIdempotentHelper 工具类手动调用，
 * 或将业务逻辑抽取为独立 Service 方法后使用此注解。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentMessage {

    /**
     * 幂等 Key 前缀，默认取类名+方法名
     */
    String prefix() default "";

    /**
     * 消息 Key 的 SpEL 表达式，可从消息体中提取业务 ID
     */
    String key() default "";

    /**
     * 幂等标记过期时间，默认 86400 秒（24 小时）
     */
    long ttl() default 86400;

    /**
     * 时间单位，默认秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 幂等标记过期后是否拒绝
     * true=过期后也拒绝（防止业务追溯），false=过期后允许重试
     */
    boolean rejectAfterExpiry() default true;
}
