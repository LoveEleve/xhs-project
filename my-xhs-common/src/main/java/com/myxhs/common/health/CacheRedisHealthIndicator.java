package com.myxhs.common.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Objects;

/**
 * Cache Redis 健康检查指示器
 * <p>
 * 检查 Cache Redis（16380）连接状态。
 * 由于项目使用双 Redis 实例（Business + Cache），Spring Boot Actuator 内置的
 * RedisHealthIndicator 只检查 @Primary（Business Redis），Cache Redis 需要单独检查。
 * </p>
 * <p>
 * 此 HealthIndicator 检查 Cache Redis 的 PING 响应，不可用时标记为 DOWN，
 * 防止流量打到 Cache Redis 不可用的实例导致 Feed 缓存全量穿透。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class CacheRedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory cacheRedisConnectionFactory;

    @Override
    public Health health() {
        try (RedisConnection connection = cacheRedisConnectionFactory.getConnection()) {
            String pong = Objects.toString(connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up()
                        .withDetail("cacheRedis", "connected")
                        .build();
            }
            log.warn("[健康检查] Cache Redis PING 响应异常: {}", pong);
            return Health.down()
                    .withDetail("cacheRedis", "unexpected response: " + pong)
                    .build();
        } catch (Exception e) {
            log.error("[健康检查] Cache Redis 连接失败", e);
            return Health.down()
                    .withDetail("cacheRedis", e.getMessage())
                    .build();
        }
    }
}
