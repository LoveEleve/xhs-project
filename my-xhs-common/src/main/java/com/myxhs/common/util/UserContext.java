package com.myxhs.common.util;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 用户上下文工具
 * <p>
 * 基于 TransmittableThreadLocal 存储当前请求的用户信息。
 * 相比原生 ThreadLocal，TransmittableThreadLocal 能在线程池（@Async）、
 * CompletableFuture 等场景下自动传递上下文，避免用户信息丢失。
 * </p>
 * <p>
 * Gateway 在鉴权通过后将 userId 注入到 X-User-Id Header 中，
 * 下游服务通过拦截器提取 Header 并存入 ThreadLocal，业务代码通过此工具获取。
 * </p>
 * <p>
 * 注意：请求结束后必须调用 clear() 清理 ThreadLocal，防止内存泄漏。
 * 建议在 HandlerInterceptor.afterCompletion() 中调用。
 * </p>
 */
public final class UserContext {

    private static final TransmittableThreadLocal<Long> USER_ID_HOLDER = new TransmittableThreadLocal<>();

    private UserContext() {
        // 工具类禁止实例化
    }

    /**
     * 设置当前用户 ID
     */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /**
     * 获取当前用户 ID
     *
     * @return 用户 ID，未登录返回 null
     */
    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    /**
     * 获取当前用户 ID（非空，未登录抛异常）
     *
     * @return 用户 ID
     * @throws IllegalStateException 如果用户未登录
     */
    public static Long requireUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new IllegalStateException("用户未登录，无法获取 userId");
        }
        return userId;
    }

    /**
     * 清理 ThreadLocal（防止内存泄漏）
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
