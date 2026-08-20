package com.myxhs.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * JVM 死锁监控指标（2026-08-13 新增）
 * <p>
 * 背景：micrometer-core 1.12.5 无 JvmDeadlockMetrics binder（Boot 3.5 亦无自动配置），
 * 标准配置 management.metrics.enable.jvm.threads.deadlocked 无效（无 binder）。
 * 本 Binder 用 JDK ThreadMXBean.findDeadlockedThreads() 补齐 jvm.threads.deadlocked。
 * 开销：每次指标采集调一次死锁检测（线程锁图遍历，线程规模下微秒级，可接受）。
 * 兜底：JVM 不支持竞争监测时返回 0 并告警一次（避免采集异常污染）。
 * </p>
 */
@Slf4j
@Component
public class JvmDeadlockMetricsBinder implements MeterBinder {

    private static volatile boolean warned = false;

    @Override
    public void bindTo(MeterRegistry registry) {
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        Gauge.builder("jvm.threads.deadlocked", threadMxBean, mx -> {
            try {
                if (!mx.isThreadContentionMonitoringSupported()) {
                    warnOnce();
                    return 0;
                }
                long[] ids = mx.findDeadlockedThreads();
                return ids == null ? 0 : ids.length;
            } catch (Exception e) {
                warnOnce();
                return 0;
            }
        })
        .description("检测到的死锁线程数")
        .register(registry);
        log.info("[指标] jvm.threads.deadlocked 已注册（自定义 Binder，micrometer 1.12.5 无内置）");
    }

    private void warnOnce() {
        if (!warned) {
            warned = true;
            log.warn("[指标] 线程竞争监测不可用，jvm.threads.deadlocked 恒为 0");
        }
    }
}
