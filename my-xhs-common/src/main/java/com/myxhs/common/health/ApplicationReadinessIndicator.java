package com.myxhs.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;

/**
 * 应用就绪健康检查指示器
 * <p>
 * 检测应用自身的运行状态（内存、线程），而非外部依赖。
 * 外部依赖（Redis、DB）的健康检查由 Spring Boot Actuator 内置的
 * RedisHealthIndicator、DataSourceHealthIndicator 自动处理。
 * </p>
 * <p>
 * 检测项：
 * 1. 堆内存使用率 — 超过 90% 标记为 DOWN（可能即将 OOM）
 * 2. 死锁线程检测 — 存在死锁标记为 DOWN
 * </p>
 * <p>
 * 为什么不重复实现 Redis 健康检查？
 * Spring Boot Actuator + spring-data-redis 已自带 RedisHealthIndicator，
 * 会自动注册到 /actuator/health 端点。重复实现会导致：
 * 1. Bean 名称冲突
 * 2. 连接泄漏（手动 getConnection() 需要手动关闭）
 * 3. 维护两份逻辑
 * </p>
 */
@Slf4j
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class ApplicationReadinessIndicator implements HealthIndicator {

    /** 堆内存使用率告警阈值 */
    private static final double HEAP_USAGE_THRESHOLD = 0.90;

    @Override
    public Health health() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // 1. 检测堆内存使用率
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax : 0;

        // 2. 检测死锁线程
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        boolean hasDeadlock = deadlockedThreads != null && deadlockedThreads.length > 0;

        Health.Builder builder = Health.up();

        // 堆内存超过 90% → DOWN
        if (heapUsagePercent > HEAP_USAGE_THRESHOLD) {
            builder = Health.down();
            log.warn("[健康检查] 堆内存使用率过高: {}%，阈值: {}%",
                    String.format("%.1f", heapUsagePercent * 100),
                    String.format("%.1f", HEAP_USAGE_THRESHOLD * 100));
        }

        // 存在死锁 → DOWN
        if (hasDeadlock) {
            builder = Health.down();
            log.error("[健康检查] 检测到死锁线程，数量: {}", deadlockedThreads.length);
        }

        return builder
                .withDetail("heapUsed", formatBytes(heapUsed))
                .withDetail("heapMax", formatBytes(heapMax))
                .withDetail("heapUsagePercent", String.format("%.1f%%", heapUsagePercent * 100))
                .withDetail("threadCount", threadBean.getThreadCount())
                .withDetail("deadlockedThreads", hasDeadlock ? deadlockedThreads.length : 0)
                .build();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }
}
