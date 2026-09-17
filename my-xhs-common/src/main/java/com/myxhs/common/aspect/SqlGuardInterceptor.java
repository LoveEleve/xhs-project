package com.myxhs.common.aspect;

import com.myxhs.common.exception.SqlGuardBlockedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SQL 守护拦截器（MyBatis Interceptor）——判定 + 安全阻断
 * <p>
 * 功能：
 * 1. 慢 SQL 检测（执行时间 &gt; slow-ms 记录 WARN 日志 + 指标）
 * 2. 连续 N 次慢 SQL → 熔断判定（按 SQL 指纹）
 * 3. 冷却期后自动恢复，恢复后重新计数
 * 4. 【v2 生产化】可选阻断：仅当 {@code block-on-open=true} 且 SQL 命中
 *    {@code block-patterns} 白名单时才真正阻断（抛 SqlGuardBlockedException），
 *    其余情况保持 observe-only（只告警不阻断），避免误伤全站。
 * </p>
 * <p>
 * 配置（默认值保证行为与旧版一致：只观测、不阻断）：
 * <pre>
 * myxhs:
 *   sql-guard:
 *     enabled: true            # 总开关
 *     slow-ms: 200             # 慢 SQL 阈值
 *     breaker-threshold: 5     # 连续慢 SQL 次数阈值
 *     cooldown-ms: 30000       # 熔断冷却（到期自动恢复）
 *     block-on-open: false     # 【安全默认】是否真阻断
 *     block-patterns: ""       # 可阻断 SQL 白名单（子串匹配，逗号分隔；空=全部不阻断）
 *     exempt-patterns: ""      # 豁免名单（不计数、不阻断）
 * </pre>
 * </p>
 * <p>
 * 指标：{@code myxhs_sql_guard_slow_total} / {@code myxhs_sql_guard_open_total}
 * / {@code myxhs_sql_guard_blocked_total}（Prometheus 口径）
 * </p>
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class SqlGuardInterceptor implements Interceptor {

    @Value("${myxhs.sql-guard.enabled:true}")
    private boolean enabled;

    /** 慢 SQL 阈值（毫秒） */
    @Value("${myxhs.sql-guard.slow-ms:200}")
    private long slowMs;

    /** 连续慢 SQL 次数的熔断阈值 */
    @Value("${myxhs.sql-guard.breaker-threshold:5}")
    private int breakerThreshold;

    /** 熔断冷却时间（毫秒），默认 30 秒 */
    @Value("${myxhs.sql-guard.cooldown-ms:30000}")
    private long cooldownMs;

    /** 是否在熔断开启时真正阻断（安全默认 false：只判定不阻断） */
    @Value("${myxhs.sql-guard.block-on-open:false}")
    private boolean blockOnOpen;

    /** 可阻断 SQL 白名单（子串匹配、逗号分隔；空 = 不阻断任何 SQL） */
    @Value("${myxhs.sql-guard.block-patterns:}")
    private String blockPatterns;

    /** 豁免名单（子串匹配、逗号分隔；命中则不计数、不阻断） */
    @Value("${myxhs.sql-guard.exempt-patterns:}")
    private String exemptPatterns;

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 统计信息：Map<SQL 指纹, 连续慢 SQL 计数> */
    private final ConcurrentHashMap<String, SlowCount> slowCountMap = new ConcurrentHashMap<>();

    private volatile List<String> blockPatternList = List.of();
    private volatile List<String> exemptPatternList = List.of();

    public SqlGuardInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!enabled) {
            return invocation.proceed();
        }

        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();

        if (isExempt(sql)) {
            return invocation.proceed();
        }

        // 惰性解析模式串（@Value 注入后首次使用）
        ensurePatternsParsed();

        String sqlFingerprint = fingerprint(sql);

        // 检查是否已熔断
        SlowCount count = slowCountMap.get(sqlFingerprint);
        if (count != null && count.isCircuitOpen(breakerThreshold)) {
            // 检查冷却是否到期
            if (System.currentTimeMillis() - count.lastSlowTime > cooldownMs) {
                log.warn("[SqlGuard] 熔断冷却到期，恢复: fingerprint={}", sqlFingerprint);
                slowCountMap.remove(sqlFingerprint);
            } else if (canBlock(sql)) {
                // 生产化阻断：仅白名单内 SQL 才阻断，避免误伤
                increment("myxhs.sql.guard.blocked", "SQL 阻断计数");
                log.error("[SqlGuard] SQL阻断: fingerprint={}, consecutiveSlows={}, sql={}",
                        sqlFingerprint, count.consecutiveSlows, abbreviate(sql));
                throw new SqlGuardBlockedException(sqlFingerprint,
                        "SQL 连续慢查询已触发保护，请稍后重试");
            } else {
                log.error("[SqlGuard] SQL已熔断(observe-only): fingerprint={}, consecutiveSlows={}",
                        sqlFingerprint, count.consecutiveSlows);
            }
        }

        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;

            if (elapsed > slowMs) {
                SlowCount newCount = slowCountMap.compute(sqlFingerprint, (k, v) -> {
                    if (v == null) return new SlowCount(1);
                    v.recordSlow();
                    return v;
                });
                increment("myxhs.sql.guard.slow", "慢 SQL 计数");

                log.warn("[SqlGuard] 慢SQL检测: fingerprint={}, elapsed={}ms, consecutiveSlows={}, sql={}",
                        sqlFingerprint, elapsed, newCount.consecutiveSlows, abbreviate(sql));

                if (newCount.consecutiveSlows >= breakerThreshold) {
                    increment("myxhs.sql.guard.open", "SQL 熔断触发计数");
                    log.error("[SqlGuard] SQL熔断触发: fingerprint={}, consecutiveSlows={}, elapsed={}ms",
                            sqlFingerprint, newCount.consecutiveSlows, elapsed);
                }
            } else {
                // SQL 恢复正常，重置计数
                slowCountMap.remove(sqlFingerprint);
            }
        }
    }

    /** 是否可阻断：开关开启 + 白名单非空 + 命中白名单 */
    private boolean canBlock(String sql) {
        if (!blockOnOpen || blockPatternList.isEmpty() || sql == null) {
            return false;
        }
        String lower = sql.toLowerCase();
        return blockPatternList.stream().anyMatch(lower::contains);
    }

    /** 豁免名单：命中则不计数、不阻断 */
    private boolean isExempt(String sql) {
        if (sql == null) {
            return true;
        }
        ensurePatternsParsed();
        if (exemptPatternList.isEmpty()) {
            return false;
        }
        String lower = sql.toLowerCase();
        return exemptPatternList.stream().anyMatch(lower::contains);
    }

    private void ensurePatternsParsed() {
        if (blockPatternList.isEmpty() && blockPatterns != null && !blockPatterns.isBlank()) {
            blockPatternList = parsePatterns(blockPatterns);
        }
        if (exemptPatternList.isEmpty() && exemptPatterns != null && !exemptPatterns.isBlank()) {
            exemptPatternList = parsePatterns(exemptPatterns);
        }
    }

    private List<String> parsePatterns(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    private void increment(String name, String description) {
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(name).description(description).register(registry).increment();
    }

    /** 生成 SQL 指纹（移除参数值，保留结构） */
    private String fingerprint(String sql) {
        if (sql == null) return "null";
        String normalized = sql.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        return normalized.hashCode() + ":" + normalized;
    }

    private String abbreviate(String sql) {
        if (sql == null) {
            return "null";
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) + "..." : normalized;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 配置通过 Spring @Value 注入，此处保留 MyBatis 原生接口兼容
    }

    /** 慢 SQL 统计内部类 */
    private static class SlowCount {
        int consecutiveSlows;
        long lastSlowTime;

        SlowCount(int initial) {
            this.consecutiveSlows = initial;
            this.lastSlowTime = System.currentTimeMillis();
        }

        void recordSlow() {
            this.consecutiveSlows++;
            this.lastSlowTime = System.currentTimeMillis();
        }

        boolean isCircuitOpen(int threshold) {
            return consecutiveSlows >= threshold;
        }
    }

}
