package com.myxhs.common.zone.redis.interceptor;

import lombok.Getter;
import org.springframework.data.redis.connection.RedisCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisListCommands;
import org.springframework.data.redis.connection.RedisSetCommands;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisHyperLogLogCommands;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Redis 命令拦截上下文。
 * <p>
 * 封装 Redis 命令执行时的上下文信息，包括连接、方法、参数等。
 *
 * @param <T> Redis 命令接口类型
 * @since 1.0.0
 */
@Getter
public class RedisMethodContext<T extends RedisCommands> {

    /** Redis 原始连接 */
    private final T connection;

    /** 被调用的方法 */
    private final Method method;

    /** 方法参数 */
    private final Object[] args;

    /** 来源 Bean 名称 */
    private final String sourceBeanName;

    /** 应用名称 */
    private final String applicationName;

    /** 来源 Bean 实例 */
    private final Object sourceBean;

    public RedisMethodContext(T connection, Method method, Object[] args,
                               String sourceBeanName, String applicationName, Object sourceBean) {
        this.connection = connection;
        this.method = method;
        this.args = args;
        this.sourceBeanName = sourceBeanName;
        this.applicationName = applicationName;
        this.sourceBean = sourceBean;
    }

    /**
     * 判断当前方法是否为写操作
     */
    public boolean isWriteMethod() {
        return isWriteMethod(false);
    }

    /**
     * 判断当前方法是否为写操作
     *
     * @param includeRead 是否包含读方法（get 类操作）
     */
    public boolean isWriteMethod(boolean includeRead) {
        String methodName = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();

        // 排除非命令接口的方法
        if (!RedisCommands.class.isAssignableFrom(declaringClass)) {
            return false;
        }

        // 读操作前缀
        if (!includeRead) {
            if (methodName.startsWith("get") || methodName.startsWith("is")
                    || methodName.startsWith("exists") || methodName.startsWith("has")
                    || methodName.startsWith("keys") || methodName.startsWith("scan")
                    || methodName.startsWith("randomKey") || methodName.startsWith("type")
                    || methodName.startsWith("dump") || methodName.startsWith("sort")
                    || methodName.startsWith("info") || methodName.startsWith("time")
                    || methodName.startsWith("echo") || methodName.startsWith("ping")
                    || methodName.startsWith("size") || methodName.startsWith("count")
                    || methodName.startsWith("length") || methodName.startsWith("indexOf")
                    || methodName.startsWith("range") || methodName.startsWith("rank")
                    || methodName.startsWith("score") || methodName.startsWith("members")
                    || methodName.startsWith("read")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "RedisMethodContext{" +
                "method=" + method.getName() +
                ", sourceBeanName='" + sourceBeanName + '\'' +
                ", applicationName='" + applicationName + '\'' +
                '}';
    }
}
