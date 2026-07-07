package com.myxhs.common.event;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一领域事件接口
 *
 * @param <T> 事件负载类型
 */
public interface DomainEvent<T> extends Serializable {

    /** 事件唯一ID */
    String getEventId();

    /** 事件类型 */
    String getEventType();

    /** 事件发生时间 */
    Instant getTimestamp();

    /** 事件来源服务 */
    String getSource();

    /** 事件负载 */
    T getPayload();
}
