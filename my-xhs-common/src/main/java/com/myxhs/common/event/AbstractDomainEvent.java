package com.myxhs.common.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件抽象基类
 * <p>
 * 提供 eventId、timestamp 的默认实现，子类只需实现 getEventType、getSource、getPayload。
 * 兼容 Lombok {@code @Data} / {@code @Builder}，子类需添加 {@code @EqualsAndHashCode(callSuper = false)}。
 * </p>
 *
 * @param <T> 事件负载类型
 */
public abstract class AbstractDomainEvent<T> implements DomainEvent<T> {

    private static final long serialVersionUID = 1L;

    /** 事件唯一ID，子类可通过构造器或 setter 覆盖 */
    protected String eventId = UUID.randomUUID().toString();

    /** 事件发生时间，子类可通过构造器或 setter 覆盖 */
    protected Instant timestamp = Instant.now();

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public abstract String getEventType();

    @Override
    public abstract String getSource();

    @Override
    public abstract T getPayload();
}
