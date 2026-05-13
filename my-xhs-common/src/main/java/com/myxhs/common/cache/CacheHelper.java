package com.myxhs.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存助手 — Cache Aside 模式封装
 * <p>
 * 封装"先查缓存 → 缓存未命中查DB → 回填缓存"的标准流程。
 * 内置防缓存穿透（缓存空值）、防缓存雪崩（TTL 随机偏移）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHelper {

    private final RedisOperator redisOperator;

    /** 延迟双删专用调度线程池（单线程守护线程，不阻塞 JVM 关闭） */
    private static final ScheduledExecutorService DELAY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-delay-delete");
                t.setDaemon(true);
                return t;
            });

    /**
     * Cache Aside 读取
     * <p>
     * 1. 查 Redis 缓存
     * 2. 缓存命中 → 直接返回（如果是空值标记则返回 null）
     * 3. 缓存未命中 → 执行 dbFallback 查 DB
     * 4. DB 查到 → 回填缓存（TTL + 随机偏移防雪崩）
     * 5. DB 未查到 → 缓存空值（短 TTL 防穿透）
     * </p>
     *
     * @param key          缓存 Key
     * @param dbFallback   DB 查询回调
     * @param timeout      缓存过期时间
     * @param unit         时间单位
     * @param <T>          返回类型
     * @return 缓存或 DB 中的数据，不存在返回 null
     */
    public <T> T getWithCacheAside(String key, Supplier<T> dbFallback, long timeout, TimeUnit unit) {
        // 1. 查缓存
        T cached = redisOperator.get(key);
        if (cached != null) {
            // 空值标记（防穿透）
            if (isNullPlaceholder(cached)) {
                log.info("[缓存] 命中空值标记(防穿透), key={}", key);
                return null;
            }
            log.info("[缓存] 命中, key={}", key);
            return cached;
        }

        // 2. 缓存未命中，查 DB
        log.info("[缓存] 未命中, 查询DB, key={}", key);
        T dbResult = dbFallback.get();

        if (dbResult != null) {
            // 3. 回填缓存（TTL + 随机偏移防雪崩）
            long timeoutSeconds = unit.toSeconds(timeout);
            long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
            redisOperator.set(key, dbResult, timeoutSeconds + randomOffset, TimeUnit.SECONDS);
            log.info("[缓存] 回填成功, key={}, TTL={}秒", key, timeoutSeconds + randomOffset);
        } else {
            // 4. 缓存空值（短 TTL 防穿透，2 分钟）
            redisOperator.set(key, "NULL_PLACEHOLDER", 2, TimeUnit.MINUTES);
            log.info("[缓存] DB未查到, 缓存空值(防穿透), key={}", key);
        }

        return dbResult;
    }

    /**
     * Cache Aside 读取（带默认 30 分钟 TTL）
     */
    public <T> T getWithCacheAside(String key, Supplier<T> dbFallback) {
        return getWithCacheAside(key, dbFallback, 30, TimeUnit.MINUTES);
    }

    /**
     * 延迟双删
     * <p>
     * 写操作时的缓存一致性策略：
     * 1. 先删缓存
     * 2. 更新 DB（由调用方执行）
     * 3. 延迟 500ms 再删缓存（异步）
     * </p>
     *
     * @param key 缓存 Key
     */
    public void delayDoubleDelete(String key) {
        // 第一次删除
        redisOperator.delete(key);

        // 延迟 500ms 第二次删除（使用调度线程池，不占用 ForkJoinPool 线程）
        DELAY_SCHEDULER.schedule(() -> {
            try {
                redisOperator.delete(key);
                log.info("[延迟双删] 第二次删除完成, key={}", key);
            } catch (Exception e) {
                log.warn("[延迟双删] 第二次删除失败, key={}", key, e);
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 判断是否为空值占位符
     */
    private boolean isNullPlaceholder(Object value) {
        return "NULL_PLACEHOLDER".equals(value);
    }
}
