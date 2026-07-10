package com.myxhs.common.zone.redis.interceptor;

import org.springframework.data.redis.connection.RedisCommands;

/**
 * Redis 方法拦截器接口。
 * <p>
 * 责任链模式 — 多个拦截器按顺序执行。
 *
 * @since 1.0.0
 */
public interface RedisMethodInterceptor {

    /**
     * 方法执行前回调
     *
     * @param context 方法上下文
     */
    default void beforeExecute(RedisMethodContext<? extends RedisCommands> context) {
    }

    /**
     * 方法执行后回调
     *
     * @param context 方法上下文
     * @param result  执行结果
     * @param failure 执行异常（null 表示成功）
     */
    default void afterExecute(RedisMethodContext<? extends RedisCommands> context, Object result, Throwable failure) {
    }

    /**
     * 错误处理
     *
     * @param context      方法上下文
     * @param beforePhase  是否在 before 阶段出错
     * @param result       执行结果
     * @param failure      执行异常
     * @param handlerError 处理器自身异常
     */
    default void handleError(RedisMethodContext<? extends RedisCommands> context,
                              boolean beforePhase, Object result, Throwable failure, Throwable handlerError) {
    }

    /**
     * 获取拦截器执行顺序
     */
    default int getOrder() {
        return 0;
    }
}
