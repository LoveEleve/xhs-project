package com.myxhs.common.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 缓存删除 MQ 消息体
 * <p>
 * 当 deleteAfterUpdate 重试 3 次仍失败时，发送此消息到 MQ，
 * 由各服务的缓存兜底消费者异步重试删除。
 * </p>
 * <p>
 * Topic: CACHE_EVICT_TOPIC
 * Tag: 服务名（如 user-service / content-service）
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 需要删除的缓存 Key */
    private String key;

    /** 来源服务名 */
    private String source;

    /** 重试次数（消费者每次消费后 +1，超过 5 次进死信队列） */
    private int retryCount;

    /** 消息创建时间戳 */
    private long timestamp;

    /** MQ Topic 常量 */
    public static final String TOPIC = "CACHE_EVICT_TOPIC";
}
