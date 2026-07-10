package com.myxhs.common.zone.redis.wrapper;

import com.myxhs.common.zone.redis.interceptor.RedisMethodInterceptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

/**
 * RedisTemplate 包装器 — Proxy 模式。
 * <p>
 * 继承 RedisTemplate，重写 preProcessConnection() 方法，
 * 在获取 Redis 连接时注入 JDK 动态代理实现命令拦截。
 *
 * @param <K> Key 类型
 * @param <V> Value 类型
 * @since 1.0.0
 */
public class RedisTemplateWrapper<K, V> extends RedisTemplate<K, V> {

    private final RedisTemplate<K, V> delegate;
    private final String beanName;
    private final String applicationName;
    private final List<RedisMethodInterceptor> interceptors;
    private volatile boolean enabled = true;

    public RedisTemplateWrapper(String beanName, RedisTemplate<K, V> delegate,
                                 String applicationName, List<RedisMethodInterceptor> interceptors) {
        this.beanName = beanName;
        this.delegate = delegate;
        this.applicationName = applicationName;
        this.interceptors = interceptors;
        init();
    }

    private void init() {
        setConnectionFactory(delegate.getConnectionFactory());
        setExposeConnection(delegate.isExposeConnection());
        setEnableDefaultSerializer(delegate.isEnableDefaultSerializer());
        setDefaultSerializer(delegate.getDefaultSerializer());
        setKeySerializer(delegate.getKeySerializer());
        setValueSerializer(delegate.getValueSerializer());
        setHashKeySerializer(delegate.getHashKeySerializer());
        setHashValueSerializer(delegate.getHashValueSerializer());
        setStringSerializer(delegate.getStringSerializer());
    }

    @Override
    protected RedisConnection preProcessConnection(RedisConnection connection, boolean existingConnection) {
        if (enabled && interceptors != null && !interceptors.isEmpty()) {
            return InterceptingRedisConnectionInvocationHandler.createProxy(
                    connection, delegate, beanName, applicationName, interceptors);
        }
        return connection;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RedisTemplate<K, V> getDelegate() {
        return delegate;
    }

    public String getBeanName() {
        return beanName;
    }
}
