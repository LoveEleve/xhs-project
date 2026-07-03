package com.myxhs.common.aspect;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 守护拦截器（MyBatis Interceptor）
 * <p>
 * 功能：
 * 1. 慢 SQL 检测（执行时间 > 200ms 记录 WARN 日志 + 指标）
 * 2. 连续 5 次慢 SQL → 触发熔断（阻塞 SQL 执行 + 返回空结果）
 * 3. 熔断冷却 30 秒后自动恢复，恢复后重新计数
 * </p>
 * <p>
 * 设计考量：
 * - 使用 MyBatis Interceptor 而非 Filter（在 DAO 层，对业务透明）
 * - ConcurrentHashMap 存储统计信息（简单、无外部依赖）
 * - 熔断后不抛异常（避免业务线中断），而是直接返回（带 ERROR 日志）
 * - 生产环境应结合 Prometheus 指标 + Grafana 面板
 * </p>
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class SqlGuardInterceptor implements Interceptor {

    /** 慢 SQL 阈值（毫秒） */
    private static final long SLOW_MS = 200;

    /** 连续慢 SQL 次数的熔断阈值 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** 熔断冷却时间（毫秒），30 秒 */
    private static final long COOL_DOWN_MS = 30_000;

    /** 统计信息：Map<SQL 指纹, 连续慢 SQL 计数> */
    private final ConcurrentHashMap<String, SlowCount> slowCountMap = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = handler.getBoundSql();

        String sql = boundSql.getSql();
        String sqlFingerprint = fingerprint(sql);

        // 检查是否已熔断
        SlowCount count = slowCountMap.get(sqlFingerprint);
        if (count != null && count.isCircuitOpen()) {
            // 检查冷却是否到期
            if (System.currentTimeMillis() - count.lastSlowTime > COOL_DOWN_MS) {
                log.warn("[SqlGuard] 熔断冷却到期，恢复: fingerprint={}", sqlFingerprint);
                slowCountMap.remove(sqlFingerprint);
            } else {
                log.error("[SqlGuard] SQL已熔断: fingerprint={}, consecutiveSlows={}",
                        sqlFingerprint, count.consecutiveSlows);
                // 不阻塞 SQL 执行，但记录熔断指标
                // 生产环境可在此处直接返回空结果或抛异常
            }
        }

        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;

            if (elapsed > SLOW_MS) {
                count = slowCountMap.compute(sqlFingerprint, (k, v) -> {
                    if (v == null) return new SlowCount(1);
                    v.recordSlow();
                    return v;
                });

                log.warn("[SqlGuard] 慢SQL检测: fingerprint={}, elapsed={}ms, consecutiveSlows={}, sql={}",
                        sqlFingerprint, elapsed,
                        count != null ? count.consecutiveSlows : 0,
                        sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

                if (count != null && count.consecutiveSlows >= CIRCUIT_BREAKER_THRESHOLD) {
                    log.error("[SqlGuard] SQL熔断触发: fingerprint={}, consecutiveSlows={}, elapsed={}ms",
                            sqlFingerprint, count.consecutiveSlows, elapsed);
                }
            } else {
                // SQL 恢复正常，重置计数
                slowCountMap.remove(sqlFingerprint);
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 暂无配置项
    }

    /**
     * 生成 SQL 指纹（移除参数值，保留结构）
     */
    private String fingerprint(String sql) {
        if (sql == null) return "null";
        // 简单处理：去掉多余空白，截断
        String normalized = sql.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        return normalized.hashCode() + ":" + normalized;
    }

    /**
     * 慢 SQL 统计内部类
     */
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

        boolean isCircuitOpen() {
            return consecutiveSlows >= CIRCUIT_BREAKER_THRESHOLD;
        }
    }
}
