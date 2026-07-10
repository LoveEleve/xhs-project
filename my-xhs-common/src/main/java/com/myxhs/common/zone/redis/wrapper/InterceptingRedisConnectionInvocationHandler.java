package com.myxhs.common.zone.redis.wrapper;

import com.myxhs.common.zone.redis.interceptor.RedisMethodContext;
import com.myxhs.common.zone.redis.interceptor.RedisMethodInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static java.lang.System.identityHashCode;

/**
 * JDK 动态代理 InvocationHandler — 拦截所有 RedisConnection 方法调用。
 * <p>
 * 对每次方法调用：
 * <ol>
 *   <li>短路处理 equals/hashCode/getDelegate</li>
 *   <li>创建 RedisMethodContext</li>
 *   <li>依次调用 beforeExecute → method.invoke → afterExecute</li>
 * </ol>
 *
 * @since 1.0.0
 */
@Slf4j
public class InterceptingRedisConnectionInvocationHandler implements InvocationHandler {

    private static final String HASH_CODE = "hashCode";
    private static final String EQUALS = "equals";
    private static final String GET_DELEGATE = "getDelegate";

    private final RedisConnection rawConnection;
    private final Object sourceBean;
    private final String sourceBeanName;
    private final String applicationName;
    private final List<RedisMethodInterceptor> interceptors;

    public InterceptingRedisConnectionInvocationHandler(
            RedisConnection rawConnection,
            Object sourceBean,
            String sourceBeanName,
            String applicationName,
            List<RedisMethodInterceptor> interceptors) {
        this.rawConnection = rawConnection;
        this.sourceBean = sourceBean;
        this.sourceBeanName = sourceBeanName;
        this.applicationName = applicationName;
        this.interceptors = interceptors;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        // 短路处理特殊方法
        if (EQUALS.equals(methodName)) {
            return proxy == args[0];
        }
        if (HASH_CODE.equals(methodName)) {
            return identityHashCode(proxy);
        }
        if (GET_DELEGATE.equals(methodName)) {
            return this.rawConnection;
        }

        method.setAccessible(true);

        RedisMethodContext<RedisCommands> context = new RedisMethodContext<>(
                (RedisCommands) rawConnection, method, args,
                sourceBeanName, applicationName, sourceBean);

        Object result = null;
        Throwable failure = null;
        try {
            beforeExecute(context);
            result = method.invoke(rawConnection, args);
        } catch (Throwable e) {
            failure = e.getCause() != null ? e.getCause() : e;
            throw failure;
        } finally {
            afterExecute(context, result, failure);
        }
        return result;
    }

    private void beforeExecute(RedisMethodContext<RedisCommands> context) {
        if (interceptors != null) {
            for (RedisMethodInterceptor interceptor : interceptors) {
                try {
                    interceptor.beforeExecute(context);
                } catch (Throwable e) {
                    interceptor.handleError(context, true, null, null, e);
                    log.error("Interceptor beforeExecute failed: {}", interceptor.getClass().getName(), e);
                }
            }
        }
    }

    private void afterExecute(RedisMethodContext<RedisCommands> context, Object result, Throwable failure) {
        if (interceptors != null) {
            for (RedisMethodInterceptor interceptor : interceptors) {
                try {
                    interceptor.afterExecute(context, result, failure);
                } catch (Throwable e) {
                    interceptor.handleError(context, false, result, failure, e);
                    log.error("Interceptor afterExecute failed: {}", interceptor.getClass().getName(), e);
                }
            }
        }
    }

    /**
     * 创建代理 RedisConnection
     */
    public static RedisConnection createProxy(
            RedisConnection rawConnection,
            Object sourceBean,
            String sourceBeanName,
            String applicationName,
            List<RedisMethodInterceptor> interceptors) {
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(),
                new Class[]{RedisConnection.class},
                new InterceptingRedisConnectionInvocationHandler(
                        rawConnection, sourceBean, sourceBeanName, applicationName, interceptors));
    }
}
