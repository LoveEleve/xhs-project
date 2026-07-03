package com.myxhs.common.cache;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存助手 — Cache Aside 模式封装 + 缓存一致性三重保障
 * <p>
 * 读操作：
 * - {@link #getWithCacheAside} — 标准 Cache Aside（先缓存→Miss→查DB→回填）
 * - {@link #getWithCacheAsideLock} — 分布式锁防缓存击穿（高并发热点 Key 场景）
 * </p>
 * <p>
 * 写操作（缓存一致性三重保障）：
 * - L1 {@link #deleteAfterUpdate} — 先更新 DB → 再删缓存（重试 3 次）
 * - L2 {@link #delayDoubleDelete} — 延迟双删（覆盖并发读回填的旧值）
 * - L3 MQ 兜底 — 删缓存失败时发 MQ 消息，消费者异步重试（由调用方集成）
 * </p>
 * <p>
 * 内置防缓存穿透（缓存空值）、防缓存雪崩（TTL 随机偏移）、防缓存击穿（分布式锁）。
 * </p>
 */
@Slf4j
@Component
public class CacheHelper {

    private final RedisOperator redisOperator;
    private final RedissonClient redissonClient;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /** 空值占位符常量（使用不可能出现在业务数据中的特殊前缀） */
    private static final String NULL_PLACEHOLDER = "\u0000__CACHE_NULL__\u0000";

    /** 延迟双删专用调度线程池（实例字段，与 Spring Bean 生命周期一致） */
    private final ScheduledExecutorService delayScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-delay-delete");
                t.setDaemon(true);
                return t;
            });

    public CacheHelper(RedisOperator redisOperator, RedissonClient redissonClient) {
        this.redisOperator = redisOperator;
        this.redissonClient = redissonClient;
    }

    /**
     * 优雅关闭延迟双删线程池
     */
    @PreDestroy
    public void shutdown() {
        log.info("[缓存] 关闭延迟双删线程池...");
        delayScheduler.shutdown();
        try {
            if (!delayScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                delayScheduler.shutdownNow();
                log.warn("[缓存] 延迟双删线程池强制关闭");
            } else {
                log.info("[缓存] 延迟双删线程池已优雅关闭");
            }
        } catch (InterruptedException e) {
            delayScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 读操作 ====================

    /**
     * Cache Aside 读取（标准版）
     * <p>
     * 1. 查 Redis 缓存
     * 2. 缓存命中 → 直接返回（空值标记返回 null）
     * 3. 缓存未命中 → 执行 dbFallback 查 DB
     * 4. DB 查到 → 回填缓存（TTL + 随机偏移防雪崩）
     * 5. DB 未查到 → 缓存空值（短 TTL 防穿透）
     * </p>
     * <p>
     * 适用场景：普通读多写少（用户信息、笔记详情等），QPS < 1000 的场景。
     * 高并发热点 Key 场景请使用 {@link #getWithCacheAsideLock}。
     * </p>
     */
    public <T> T getWithCacheAside(String key, Supplier<T> dbFallback, long timeout, TimeUnit unit) {
        // 1. 查缓存（Redis 不可用 → 降级查 DB，不回填缓存避免加重 Redis 压力）
        T cached;
        try {
            cached = redisOperator.get(key);
        } catch (com.myxhs.common.exception.RedisUnavailableException e) {
            log.warn("[缓存] Redis不可用，降级查DB(不回填), key={}", key, e);
            return dbFallback.get();
        }

        if (cached != null) {
            if (isNullPlaceholder(cached)) {
                return null;
            }
            return cached;
        }

        // 2. 缓存未命中，查 DB
        T dbResult = dbFallback.get();

        // 3. 回填缓存（Redis 不可用时跳过回填，不中断业务）
        try {
            if (dbResult != null) {
                long timeoutSeconds = unit.toSeconds(timeout);
                long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
                redisOperator.set(key, dbResult, timeoutSeconds + randomOffset, TimeUnit.SECONDS);
            } else {
                redisOperator.set(key, NULL_PLACEHOLDER, 2, TimeUnit.MINUTES);
            }
        } catch (com.myxhs.common.exception.RedisUnavailableException e) {
            log.warn("[缓存] 回填缓存失败-Redis不可用, key={}", key, e);
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
     * Cache Aside 读取 + 分布式锁防缓存击穿（Singleflight 模式）
     * <p>
     * 适用场景：高并发热点 Key（如秒杀商品详情），缓存失效瞬间大量请求同时穿透到 DB。
     * </p>
     * <p>
     * 原理：缓存未命中时，只有一个线程获取分布式锁去查 DB 并回填缓存，
     * 其他线程等待锁释放后直接读缓存。避免 DB 被瞬间打爆。
     * </p>
     * <p>
     * 锁粒度：按 Key 加锁（lock:cache:{key}），不同 Key 互不影响。
     * 锁超时：等待 3 秒，持有 10 秒（足够完成一次 DB 查询 + 缓存回填）。
     * </p>
     */
    public <T> T getWithCacheAsideLock(String key, Supplier<T> dbFallback, long timeout, TimeUnit unit) {
        // 1. 查缓存（Redis 不可用 → 降级查 DB，不获取锁）
        T cached;
        try {
            cached = redisOperator.get(key);
        } catch (com.myxhs.common.exception.RedisUnavailableException e) {
            log.warn("[缓存] Redis不可用，降级查DB(不获取锁), key={}", key, e);
            return dbFallback.get();
        }

        if (cached != null) {
            if (isNullPlaceholder(cached)) {
                return null;
            }
            return cached;
        }

        // 2. 缓存未命中，获取分布式锁
        String lockKey = "lock:cache:" + key;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (acquired) {
                try {
                    // 双重检查：获取锁后再查一次缓存（可能其他线程已回填）
                    T doubleCheck = redisOperator.get(key);
                    if (doubleCheck != null) {
                        return isNullPlaceholder(doubleCheck) ? null : doubleCheck;
                    }

                    // 查 DB 并回填
                    T dbResult = dbFallback.get();
                    try {
                        if (dbResult != null) {
                            long timeoutSeconds = unit.toSeconds(timeout);
                            long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
                            redisOperator.set(key, dbResult, timeoutSeconds + randomOffset, TimeUnit.SECONDS);
                        } else {
                            redisOperator.set(key, NULL_PLACEHOLDER, 2, TimeUnit.MINUTES);
                        }
                    } catch (com.myxhs.common.exception.RedisUnavailableException e) {
                        log.warn("[缓存] 回填缓存失败-Redis不可用, key={}", key, e);
                    }
                    return dbResult;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 获取锁失败（其他线程正在查 DB），等待后重试读缓存
                Thread.sleep(100);
                T retryCache;
                try {
                    retryCache = redisOperator.get(key);
                } catch (com.myxhs.common.exception.RedisUnavailableException e) {
                    log.warn("[缓存] Redis不可用，降级查DB, key={}", key, e);
                    return dbFallback.get();
                }
                if (retryCache != null) {
                    return isNullPlaceholder(retryCache) ? null : retryCache;
                }
                // 仍然未命中，降级为直接查 DB（不回填缓存，避免并发写入）
                log.warn("[缓存] 获取锁失败且缓存仍未命中，降级查DB, key={}", key);
                return dbFallback.get();
            }
        } catch (com.myxhs.common.exception.RedisUnavailableException e) {
            log.error("[缓存] 分布式锁操作-Redis不可用, 降级为无锁模式, key={}", key, e);
            return getWithCacheAside(key, dbFallback, timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[缓存] 获取锁被中断，降级查DB, key={}", key);
            return dbFallback.get();
        } catch (Exception e) {
            // Redisson 连接异常，降级为无锁模式
            log.error("[缓存] 分布式锁异常，降级为无锁模式, key={}", key, e);
            return getWithCacheAside(key, dbFallback, timeout, unit);
        }
    }

    // ==================== 写操作（缓存一致性） ====================

    /**
     * 写操作后删缓存（重试 3 次）
     * <p>
     * 适用场景：标准 Cache Aside 写操作。
     * 调用方先更新 DB，再调用此方法删缓存。
     * </p>
     * <p>
     * 重试策略：最多重试 3 次，每次间隔 50ms。
     * 3 次都失败时记录 ERROR 日志，等待 MQ 兜底消费者异步重试。
     * </p>
     *
     * @param keys 需要删除的缓存 Key（支持多个）
     * @return true=全部删除成功，false=至少一个删除失败
     */
    public boolean deleteAfterUpdate(String... keys) {
        boolean allSuccess = true;
        for (String key : keys) {
            boolean deleted = false;
            for (int i = 0; i < 3; i++) {
                try {
                    boolean success = redisOperator.delete(key);
                    if (success) {
                        deleted = true;
                        break;
                    }
                    // Key 不存在（Redis 返回 0），视为成功
                    if (i == 0) {
                        log.debug("[缓存] 删缓存-Key不存在(视为成功), key={}", key);
                        deleted = true;
                        break;
                    }
                } catch (com.myxhs.common.exception.RedisUnavailableException e) {
                    // Redis 不可用，重试无意义，直接失败
                    log.error("[缓存] 删缓存失败-Redis不可用, key={}", key, e);
                    allSuccess = false;
                    break;
                }
                log.warn("[缓存] 删缓存重试 {}/3, key={}", i + 1, key);
                if (i < 2) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            if (!deleted) {
                log.error("[缓存] 删缓存失败(3次重试均失败)，等待MQ兜底, key={}", key);
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 延迟双删
     * <p>
     * 强一致场景的缓存一致性策略（商品上下架、笔记审核等）。
     * 调用方应在 DB 更新之后调用此方法。
     * </p>
     * <p>
     * 执行流程：
     * 1. 立即删缓存（第一次删）
     * 2. 延迟 500ms 再删缓存（第二次删，覆盖并发读回填的旧值）
     * </p>
     * <p>
     * 为什么需要两次删？
     * - 第一次删：清除当前缓存，让后续读请求查 DB 获取最新值
     * - 第二次删（延迟 500ms）：覆盖在"DB 更新 → 第一次删"之间，
     *   并发读请求从从库读到旧值并回填缓存的情况
     * - 500ms = 主从同步延迟(~200ms) + 业务读耗时(~100ms) + 安全余量(~200ms)
     * </p>
     *
     * @param key 缓存 Key
     */
    public void delayDoubleDelete(String key) {
        // 第一次删除（带重试，Redis 不可用时立即失败）
        try {
            boolean firstDeleted = redisOperator.delete(key);
            if (!firstDeleted) {
                log.debug("[延迟双删] 第一次删除-Key不存在, key={}", key);
            }
        } catch (com.myxhs.common.exception.RedisUnavailableException e) {
            log.error("[延迟双删] 第一次删除失败-Redis不可用, key={}", key, e);
            // Redis 不可用，不调度第二次（第二次也会失败）
            return;
        }

        // 延迟 500ms 第二次删除
        delayScheduler.schedule(() -> {
            try {
                boolean secondDeleted = redisOperator.delete(key);
                log.debug("[延迟双删] 第二次删除完成, key={}, deleted={}", key, secondDeleted);
            } catch (com.myxhs.common.exception.RedisUnavailableException e) {
                log.error("[延迟双删] 第二次删除失败-Redis不可用, key={}", key, e);
                // MQ 兜底：Redis 不可用时也发 MQ，消费者在 Redis 恢复后重试
                if (rocketMQTemplate != null) {
                    try {
                        rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key, 3000);
                    } catch (Exception mqEx) {
                        log.error("[延迟双删] MQ兜底发送失败, key={}", key, mqEx);
                    }
                }
            } catch (Exception e) {
                log.warn("[延迟双删] 第二次删除失败，发送MQ兜底, key={}", key, e);
                // MQ 兜底：发到 CACHE_EVICT_TOPIC，由缓存驱逐消费者异步重试
                if (rocketMQTemplate != null) {
                    try {
                        rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key, 3000);
                    } catch (Exception mqEx) {
                        log.error("[延迟双删] MQ兜底发送失败, key={}", key, mqEx);
                    }
                }
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 延迟双删（支持多个 Key）
     */
    public void delayDoubleDelete(String... keys) {
        for (String key : keys) {
            delayDoubleDelete(key);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否为空值占位符
     * <p>
     * 使用 Unicode NUL 字符包裹，确保不会与任何业务数据冲突。
     * </p>
     */
    private boolean isNullPlaceholder(Object value) {
        return NULL_PLACEHOLDER.equals(value);
    }

    /**
     * 获取底层 RedisOperator（供特殊场景直接操作 Redis）
     */
    public RedisOperator getRedisOperator() {
        return redisOperator;
    }
}
