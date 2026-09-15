package com.myxhs.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisSentineled;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 请求幂等（X-Request-Id）：同 key 重复提交返回首次结果，处理中返回 IN_FLIGHT。
 * 状态存 Redis，TTL 10 分钟（可配）；Redis 异常 fail-open（放行，不阻断诊断）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    public enum State { NEW, IN_FLIGHT, DONE }

    public record Result(State state, String sessionId, String reply) {
    }

    private final ObjectMapper objectMapper;

    @Value("${REDIS_SENTINEL_MASTER:mymaster}")
    private String sentinelMaster;

    @Value("${REDIS_SENTINEL_NODES:192.168.0.142:26379}")
    private String sentinelNodes;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Value("${myxhs.idempotency.ttl-seconds:600}")
    private int ttlSeconds;

    private JedisSentineled jedis;

    @PostConstruct
    public void init() {
        Set<HostAndPort> sentinels = Arrays.stream(sentinelNodes.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(HostAndPort::from).collect(Collectors.toSet());
        JedisClientConfig masterConfig = DefaultJedisClientConfig.builder()
                .password(redisPassword == null || redisPassword.isEmpty() ? null : redisPassword)
                .build();
        jedis = new JedisSentineled(sentinelMaster, masterConfig, sentinels,
                DefaultJedisClientConfig.builder().build());
    }

    public Result begin(long userId, String requestId) {
        try {
            String key = key(userId, requestId);
            String newValue = "IN_FLIGHT";
            String set = jedis.set(key, newValue, redis.clients.jedis.params.SetParams.setParams().nx().ex(ttlSeconds));
            if ("OK".equals(set)) {
                return new Result(State.NEW, null, null);
            }
            String existing = jedis.get(key);
            if (existing == null) {
                jedis.setex(key, ttlSeconds, newValue);
                return new Result(State.NEW, null, null);
            }
            if ("IN_FLIGHT".equals(existing)) {
                return new Result(State.IN_FLIGHT, null, null);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> cached = objectMapper.readValue(existing.substring("DONE:".length()), Map.class);
            return new Result(State.DONE, cached.get("sessionId"), cached.get("reply"));
        } catch (Exception e) {
            log.warn("[幂等] begin 降级放行: {}", e.getMessage());
            return new Result(State.NEW, null, null);
        }
    }

    public void complete(long userId, String requestId, String sessionId, String reply) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "sessionId", sessionId == null ? "" : sessionId,
                    "reply", reply == null ? "" : reply));
            jedis.setex(key(userId, requestId), ttlSeconds, "DONE:" + payload);
        } catch (Exception e) {
            log.warn("[幂等] complete 失败: {}", e.getMessage());
        }
    }

    public void fail(long userId, String requestId) {
        try {
            jedis.del(key(userId, requestId));
        } catch (Exception e) {
            log.warn("[幂等] fail 清理失败: {}", e.getMessage());
        }
    }

    static String key(long userId, String requestId) {
        return "xhs-ai:idem:" + userId + ":" + requestId;
    }
}
