package com.myxhs.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisSentineled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据保留策略（生产化）：消息/审计按天清理，Redis Agent 状态按空闲时间设过期。
 * 默认：消息 90 天、审计 365 天、状态空闲 30 天后 7 天过期；全部可配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${myxhs.retention.message-days:90}")
    private int messageDays;

    @Value("${myxhs.retention.audit-days:365}")
    private int auditDays;

    @Value("${myxhs.retention.state-idle-days:30}")
    private int stateIdleDays;

    @Value("${REDIS_SENTINEL_MASTER:mymaster}")
    private String sentinelMaster;

    @Value("${REDIS_SENTINEL_NODES:192.168.0.142:26379}")
    private String sentinelNodes;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Scheduled(cron = "${myxhs.retention.cron:0 30 4 * * ?}")
    public void cleanup() {
        int messages = 0;
        int audits = 0;
        long states = 0;
        try {
            messages = jdbcTemplate.update(
                    "DELETE FROM ai_message WHERE created_at < NOW() - INTERVAL ? DAY", messageDays);
            audits = jdbcTemplate.update(
                    "DELETE FROM ai_audit WHERE created_at < NOW() - INTERVAL ? DAY", auditDays);
        } catch (Exception e) {
            log.warn("[保留策略] DB 清理失败: {}", e.getMessage());
        }
        try {
            states = expireIdleStates();
        } catch (Exception e) {
            log.warn("[保留策略] Redis 状态清理失败: {}", e.getMessage());
        }
        if (meterRegistry != null) {
            meterRegistry.counter("ai_retention_deleted_total", "type", "message").increment(messages);
            meterRegistry.counter("ai_retention_deleted_total", "type", "audit").increment(audits);
            meterRegistry.counter("ai_retention_deleted_total", "type", "state").increment(states);
        }
        log.info("[保留策略] 清理完成: message={}(>{}d), audit={}(>{}d), state={}(idle>{}d)",
                messages, messageDays, audits, auditDays, states, stateIdleDays);
    }

    /** 扫描 Agent 状态键，空闲超阈值则设 7 天过期（不做硬删，留恢复窗口） */
    long expireIdleStates() {
        Set<HostAndPort> sentinels = Arrays.stream(sentinelNodes.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(HostAndPort::from).collect(Collectors.toSet());
        JedisClientConfig masterConfig = DefaultJedisClientConfig.builder()
                .password(redisPassword == null || redisPassword.isEmpty() ? null : redisPassword)
                .build();
        try (JedisSentineled jedis = new JedisSentineled(sentinelMaster, masterConfig, sentinels,
                DefaultJedisClientConfig.builder().build())) {
            long threshold = stateIdleDays * 86400L;
            String cursor = "0";
            long expired = 0;
            do {
                ScanResult<String> result = jedis.scan(cursor,
                        new ScanParams().match("xhs-ai:state:*").count(200));
                for (String key : result.getResult()) {
                    Long idle = jedis.objectIdletime(key);
                    if (idle != null && idle > threshold) {
                        jedis.expire(key, 7 * 86400);
                        expired++;
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return expired;
        }
    }
}
