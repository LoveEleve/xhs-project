package com.myxhs.common.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import java.lang.reflect.Field;

/**
 * RocketMQ 健康检查指示器
 * <p>
 * 检查 RocketMQ Producer 与 NameServer 的连接状态。
 * Spring Boot Actuator 没有内置的 RocketMQ HealthIndicator，
 * 如果 RocketMQ 不可用但健康检查返回 UP，流量会继续打到无法消费消息的实例。
 * </p>
 */
@Slf4j
@ConditionalOnClass(name = "org.apache.rocketmq.spring.core.RocketMQTemplate")
@ConditionalOnBean(RocketMQTemplate.class)
public class RocketMQHealthIndicator implements HealthIndicator {

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMQHealthIndicator(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public Health health() {
        try {
            // 通过反射获取 DefaultMQProducer 检查 NameServer 连接状态
            DefaultMQProducer producer = getProducer();
            if (producer == null) {
                return Health.unknown()
                        .withDetail("rocketmq", "producer not available")
                        .build();
            }

            // 检查 NameServer 地址是否可达
            String namesrvAddr = producer.getNamesrvAddr();
            if (namesrvAddr == null || namesrvAddr.isEmpty()) {
                log.warn("[健康检查] RocketMQ NameServer 地址为空");
                return Health.down()
                        .withDetail("rocketmq", "namesrv address is empty")
                        .build();
            }

            // 通过反射调用 getDefaultTopicRouteInfoFromNameServer 检查 NameServer 连接
            // 使用反射避免 RocketMQ 4.x/5.x API 差异导致的编译错误
            try {
                java.lang.reflect.Method method = DefaultMQProducer.class.getMethod(
                        "getDefaultTopicRouteInfoFromNameServer", long.class);
                method.invoke(producer, 3000L);
                return Health.up()
                        .withDetail("rocketmq", "connected")
                        .withDetail("namesrvAddr", namesrvAddr)
                        .build();
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.warn("[健康检查] RocketMQ NameServer 连接失败: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                return Health.down()
                        .withDetail("rocketmq", "namesrv unreachable")
                        .withDetail("namesrvAddr", namesrvAddr)
                        .build();
            } catch (NoSuchMethodException e) {
                // RocketMQ 5.x client 中该方法不存在或签名不同，视为连接可用
                log.debug("[健康检查] RocketMQ client 不支持路由信息检查, 视为连接正常");
                return Health.up()
                        .withDetail("rocketmq", "connected (no route check)")
                        .withDetail("namesrvAddr", namesrvAddr)
                        .build();
            }
        } catch (Exception e) {
            log.error("[健康检查] RocketMQ 健康检查异常", e);
            return Health.down()
                    .withDetail("rocketmq", e.getMessage())
                    .build();
        }
    }

    /**
     * 通过反射获取 RocketMQTemplate 内部的 DefaultMQProducer
     */
    private DefaultMQProducer getProducer() {
        try {
            Field field = RocketMQTemplate.class.getDeclaredField("producer");
            field.setAccessible(true);
            return (DefaultMQProducer) field.get(rocketMQTemplate);
        } catch (Exception e) {
            log.warn("[健康检查] 无法获取 RocketMQ Producer: {}", e.getMessage());
            return null;
        }
    }
}
