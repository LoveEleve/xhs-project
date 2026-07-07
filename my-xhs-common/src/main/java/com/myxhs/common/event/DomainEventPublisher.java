package com.myxhs.common.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 统一事件发布器
 * <p>
 * 支持三种发布方式：
 * <ul>
 *   <li>同步发布（Spring ApplicationEvent）</li>
 *   <li>异步发布（通过 {@code @Async} + ApplicationEvent）</li>
 *   <li>分布式发布（由各模块自行实现 RocketMQ 发送逻辑）</li>
 * </ul>
 * </p>
 */
@Component
public class DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public DomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 同步发布事件
     *
     * @param event 领域事件
     * @param <T>   事件负载类型
     */
    public <T> void publishSync(DomainEvent<T> event) {
        applicationEventPublisher.publishEvent(event);
    }
}
