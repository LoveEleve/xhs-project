package com.myxhs.common.mq;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MQ/重试退避工具（P2/2026-09-27）
 * <p>
 * 背景：全链路重试退避均为固定值（无抖动）——多实例/多服务同时重试会"对齐共振"，
 * 下游恢复瞬间被二次打垮；补发任务缺少指数退避时还会高频捶打故障 Broker。
 * 本工具统一提供"指数退避（封顶）+ 抖动"计算。
 * </p>
 */
public final class RetryBackoffUtils {

    private RetryBackoffUtils() {
    }

    /**
     * 指数退避（秒）：base * 2^(attempt-1)，封顶 maxSeconds，再叠加 ±jitterRatio 抖动。
     *
     * @param attempt     第几次重试（从 1 开始）
     * @param baseSeconds 基础退避（秒）
     * @param maxSeconds  封顶（秒）
     * @param jitterRatio 抖动比例（如 0.2 = ±20%）
     */
    public static long exponentialSeconds(int attempt, long baseSeconds, long maxSeconds, double jitterRatio) {
        long delay = Math.max(0, baseSeconds);
        for (int i = 1; i < attempt && delay < maxSeconds; i++) {
            delay = Math.min(delay * 2, maxSeconds);
        }
        return jitter(delay, jitterRatio);
    }

    /** 给任意毫秒退避叠加 ±jitterRatio 抖动（结果不为负） */
    public static long jitter(long baseMillis, double jitterRatio) {
        if (baseMillis <= 0 || jitterRatio <= 0) {
            return Math.max(0, baseMillis);
        }
        long delta = (long) (baseMillis * jitterRatio);
        if (delta <= 0) {
            return baseMillis;
        }
        long jittered = baseMillis - delta + (long) (ThreadLocalRandom.current().nextDouble() * (2 * delta + 1));
        return Math.max(1, jittered);
    }
}
