package com.myxhs.common.zone.redis.event;

import com.myxhs.common.zone.redis.interceptor.RedisMethodContext;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.data.redis.connection.RedisCommands;

import java.lang.reflect.Method;

/**
 * Redis 命令事件。
 * <p>
 * 在 Redis 写命令执行成功后发布，携带命令方法、参数、来源等信息。
 * 可用于跨 Zone 数据复制、审计日志等场景。
 *
 * @since 1.0.0
 */
@Getter
public class RedisCommandEvent extends ApplicationEvent {

    private final String applicationName;
    private final String sourceBeanName;
    private final String methodName;
    private final String interfaceName;
    private final transient Object[] args;
    private final transient Method method;

    public RedisCommandEvent(RedisMethodContext<? extends RedisCommands> context) {
        super(context.getConnection());
        this.applicationName = context.getApplicationName();
        this.sourceBeanName = context.getSourceBeanName();
        this.method = context.getMethod();
        this.methodName = method.getName();
        this.interfaceName = method.getDeclaringClass().getName();
        this.args = context.getArgs();
    }

    @Override
    public String toString() {
        return "RedisCommandEvent{" +
                "applicationName='" + applicationName + '\'' +
                ", sourceBeanName='" + sourceBeanName + '\'' +
                ", methodName='" + methodName + '\'' +
                ", interfaceName='" + interfaceName + '\'' +
                '}';
    }
}
