package com.myxhs.ai.model;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisSentineled;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户 token 预算存储（Redis Sentinel）：按天计量、TTL 2 天。
 * 读取失败 fail-open（仅影响成本控制，不阻断诊断），写入失败仅告警。
 */
@Slf4j
@Component
public class TokenBudgetService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${REDIS_SENTINEL_MASTER:mymaster}")
    private String sentinelMaster;

    @Value("${REDIS_SENTINEL_NODES:192.168.0.142:26379}")
    private String sentinelNodes;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Value("${MYXHS_BUDGET_DAILY_TOKENS:200000}")
    private long dailyTokens;

    @Value("${MYXHS_BUDGET_SOFT_RATIO:0.8}")
    private double softRatio;

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
        log.info("[Token预算] 已启用 按用户日预算={} tokens，软限比例={}", dailyTokens, softRatio);
    }

    public long softLimit() {
        return (long) (dailyTokens * softRatio);
    }

    public long hardLimit() {
        return dailyTokens;
    }

    public long usedToday(long userId) {
        try {
            String value = jedis.get(key(userId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("[Token预算] 读取失败（fail-open）: {}", e.getMessage());
            return 0L;
        }
    }

    public void add(long userId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            String key = key(userId);
            jedis.incrBy(key, tokens);
            jedis.expire(key, 2 * 86400);
        } catch (Exception e) {
            log.warn("[Token预算] 计量写入失败: {}", e.getMessage());
        }
    }

    private String key(long userId) {
        return "xhs-ai:budget:" + userId + ":" + LocalDate.now().format(DAY);
    }
}
