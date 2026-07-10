package com.myxhs.common.zone.redis.interceptor;

import com.myxhs.common.zone.redis.event.RedisCommandEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.data.redis.connection.RedisCommands;

/**
 * 事件发布 Redis 命令拦截器。
 * <p>
 * 在 Redis 写命令执行成功后，发布 RedisCommandEvent 到 Spring 事件总线。
 * 可用于跨 Zone 数据同步、审计日志等场景。
 *
 * @since 1.0.0
 */
@Slf4j
public class EventPublishingRedisCommandInterceptor implements RedisMethodInterceptor, ApplicationEventPublisherAware {

    public static final String BEAN_NAME = "myxhsEventPublishingRedisCommandInterceptor";

    private ApplicationEventPublisher applicationEventPublisher;

    private volatile boolean enabled = true;

    @Override
    public void afterExecute(RedisMethodContext<? extends RedisCommands> context, Object result, Throwable failure) {
        if (enabled && failure == null && context.isWriteMethod()) {
            try {
                applicationEventPublisher.publishEvent(new RedisCommandEvent(context));
            } catch (Exception e) {
                log.warn("Failed to publish RedisCommandEvent for method: {}", context.getMethod().getName(), e);
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
