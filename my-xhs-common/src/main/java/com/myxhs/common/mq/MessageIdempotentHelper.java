package com.myxhs.common.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MQ 消息幂等辅助工具
 * <p>
 * 基于 Redis SET NX 实现消息去重，统一各消费者的幂等处理方式。
 * 使用方式：
 * <pre>
 * if (!idempotentHelper.isFirstProcess("topic", msgId, 86400)) {
 *     return; // 重复消息，跳过
 * }
 * try {
 *     // 业务处理
 * } catch (Exception e) {
 *     // 业务失败时删除幂等标记，允许 MQ 重试
 *     idempotentHelper.removeMark("topic", msgId);
 *     throw e;
 * }
 * </pre>
 * </p>
 * <p>
 * Redis 降级策略：Redis 不可用时降级放行（保证可用性），同时记录告警日志。
 * 如果业务需要强幂等，应在 DB 层额外设置唯一索引兜底。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIdempotentHelper {

    private static final String KEY_PREFIX = "msg:idempotent:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 检查消息是否已处理，首次处理返回 true
     *
     * @param bizType    业务类型（如 topic 名或业务标识）
     * @param bizId      业务唯一标识（如 msgId 或 orderNo）
     * @param ttlSeconds 幂等标记过期时间（秒）
     * @return true=首次处理，false=重复消息
     */
    public boolean isFirstProcess(String bizType, String bizId, long ttlSeconds) {
        String key = KEY_PREFIX + bizType + ":" + bizId;
        try {
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(success)) {
                log.debug("[消息幂等] 首次处理: bizType={}, bizId={}", bizType, bizId);
                return true;
            }
            log.warn("[消息幂等] 重复消息已忽略: bizType={}, bizId={}", bizType, bizId);
            return false;
        } catch (Exception e) {
            // Redis 不可用时降级放行（保证核心业务可用）
            log.error("[消息幂等] Redis不可用，降级放行: bizType={}, bizId={}", bizType, bizId, e);
            return true;
        }
    }

    /**
     * 删除幂等标记
     * <p>
     * 业务处理失败时调用，删除标记允许 MQ 重试该消息。
     * 注意：仅在业务确定未执行成功时调用，超时/网络异常等不应删除。
     * </p>
     *
     * @param bizType 业务类型
     * @param bizId   业务唯一标识
     */
    public void removeMark(String bizType, String bizId) {
        String key = KEY_PREFIX + bizType + ":" + bizId;
        try {
            stringRedisTemplate.delete(key);
            log.info("[消息幂等] 已删除幂等标记（允许重试）: bizType={}, bizId={}", bizType, bizId);
        } catch (Exception e) {
            log.error("[消息幂等] 删除幂等标记失败: bizType={}, bizId={}", bizType, bizId, e);
        }
    }
}
