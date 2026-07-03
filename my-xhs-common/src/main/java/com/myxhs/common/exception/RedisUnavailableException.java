package com.myxhs.common.exception;

/**
 * Redis 不可用异常
 * <p>
 * 当 Redis 连接断开、超时或集群不可达时抛出。
 * 与 key 不存在 / key 已存在等正常业务语义明确区分，
 * 供上层（锁切面、幂等切面、缓存层）选择降级策略。
 * </p>
 * <p>
 * 降级策略建议：
 * - 分布式锁/幂等：降级放行（fail-open，保证可用性）
 * - 缓存读：降级查 DB + 熔断器控制穿透流量
 * - 计数/库存：抛业务异常，拒绝操作
 * </p>
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message) {
        super(message);
    }

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
