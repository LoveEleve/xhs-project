package com.myxhs.common.id;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 号段模式 ID 生成器（双 Buffer 版）
 * <p>
 * 原理：从 MySQL t_id_segment 表中批量获取一段 ID（号段），缓存在内存中分配。
 * 号段用完后切换到下一个 Buffer。
 * </p>
 * <p>
 * 双 Buffer 机制：
 * 1. 维护两个号段 Segment（current 和 next），交替使用
 * 2. 当前号段使用量达到 70% 时，异步加载下一个号段到 next Buffer
 * 3. 当前号段用完时，无缝切换到 next Buffer（已预加载完毕）
 * 4. 避免号段切换时的 DB 查询延迟影响业务
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentIdGenerator {

    private final JdbcTemplate jdbcTemplate;

    /** 号段缓存：bizTag → DoubleBuffer */
    private final Map<String, DoubleBuffer> bufferMap = new ConcurrentHashMap<>();

    /** 异步预加载线程池（守护线程，不阻塞 JVM 关闭） */
    private final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "id-segment-preload");
        t.setDaemon(true);
        return t;
    });

    /**
     * 优雅关闭预加载线程池
     * <p>
     * Spring 容器关闭时调用，等待正在执行的预加载任务完成（最多 5 秒），
     * 避免号段预加载任务被强制中断导致数据不一致。
     * </p>
     */
    @PreDestroy
    public void shutdown() {
        log.info("[号段] 关闭预加载线程池...");
        preloadExecutor.shutdown();
        try {
            if (!preloadExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                preloadExecutor.shutdownNow();
                log.warn("[号段] 预加载线程池强制关闭");
            } else {
                log.info("[号段] 预加载线程池已优雅关闭");
            }
        } catch (InterruptedException e) {
            preloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取下一个号段 ID
     *
     * @param bizTag 业务标签（如 "user"、"order"）
     * @return 全局唯一的号段 ID
     */
    public long nextId(String bizTag) {
        DoubleBuffer doubleBuffer = bufferMap.computeIfAbsent(bizTag, k -> {
            DoubleBuffer db = new DoubleBuffer(this::loadSegmentFromDb, preloadExecutor);
            loadSegmentFromDb(k, db.current);
            return db;
        });

        return doubleBuffer.nextId(bizTag);
    }

    /**
     * 从 DB 加载号段（乐观锁 + 重试）
     *
     * @param bizTag  业务标签
     * @param segment 目标号段对象
     */
    private void loadSegmentFromDb(String bizTag, Segment segment) {
        int updated = 0;
        long maxId = 0;
        int step = 0;

        // 最多重试 3 次（乐观锁冲突时）
        for (int retry = 0; retry < 3; retry++) {
            Map<String, Object> row;
            try {
                row = jdbcTemplate.queryForMap(
                        "SELECT max_id, step, version FROM t_id_segment WHERE biz_tag = ?",
                        bizTag
                );
            } catch (EmptyResultDataAccessException e) {
                throw new RuntimeException(
                        "号段配置不存在，请先在 t_id_segment 表中初始化 bizTag=" + bizTag +
                        "（INSERT INTO t_id_segment (biz_tag, max_id, step, description) VALUES ('" + bizTag + "', 0, 1000, ''))");
            }
            maxId = ((Number) row.get("max_id")).longValue();
            step = ((Number) row.get("step")).intValue();
            int version = ((Number) row.get("version")).intValue();

            updated = jdbcTemplate.update(
                    "UPDATE t_id_segment SET max_id = max_id + step, version = version + 1, updated_at = NOW() " +
                            "WHERE biz_tag = ? AND version = ?",
                    bizTag, version
            );

            if (updated > 0) {
                break;
            }
            log.warn("[号段] 乐观锁冲突, bizTag={}, retry={}", bizTag, retry + 1);
        }

        if (updated == 0) {
            throw new RuntimeException("号段分配失败（乐观锁冲突超过重试次数）, bizTag=" + bizTag);
        }

        // 设置号段范围：(maxId, maxId + step]
        segment.currentId.set(maxId);
        segment.maxId = maxId + step;
        segment.step = step;
        segment.loaded = true;

        log.info("[号段] 加载成功, bizTag={}, range=({}, {}], step={}", bizTag, maxId, maxId + step, step);
    }

    /**
     * 号段加载器函数式接口（用于 static 内部类显式传入加载方法）
     */
    @FunctionalInterface
    private interface SegmentLoader {
        void load(String bizTag, Segment segment);
    }

    /**
     * 单个号段
     */
    private static class Segment {
        /** 当前 ID（原子自增） */
        final AtomicLong currentId = new AtomicLong(0);
        /** 号段最大 ID */
        volatile long maxId;
        /** 号段步长 */
        volatile int step;
        /** 是否已加载 */
        volatile boolean loaded;

        /** 重置（切换后清空，准备下次预加载） */
        void reset() {
            currentId.set(0);
            maxId = 0;
            step = 0;
            loaded = false;
        }
    }

    /**
     * 双 Buffer 容器
     * <p>
     * 维护 current 和 next 两个 Segment，交替使用。
     * 当 current 使用量达到 70% 时，异步预加载 next。
     * 当 current 用完时，切换 current ↔ next。
     * </p>
     * <p>
     * 【设计决策】使用 static 内部类，避免隐式持有外部类引用导致 GC 延迟回收。
     * 通过 SegmentLoader 函数式接口显式传入 loadSegmentFromDb 方法引用。
     * </p>
     */
    private static class DoubleBuffer {
        /** 当前使用的号段 */
        volatile Segment current = new Segment();
        /** 预加载的下一个号段 */
        volatile Segment next = new Segment();
        /** 是否正在预加载（CAS 标记，防止重复提交预加载任务） */
        volatile boolean preloading = false;
        /** 锁（用于号段切换） */
        final ReentrantLock lock = new ReentrantLock();
        /** 号段加载器（显式传入，避免持有外部类引用） */
        final SegmentLoader loader;
        /** 异步预加载线程池引用 */
        final ExecutorService preloadExecutor;

        DoubleBuffer(SegmentLoader loader, ExecutorService preloadExecutor) {
            this.loader = loader;
            this.preloadExecutor = preloadExecutor;
        }

        long nextId(String bizTag) {
            while (true) {
                long id = current.currentId.incrementAndGet();

                if (id <= current.maxId) {
                    // 正常范围内，检查是否需要触发预加载
                    long threshold = current.maxId - (long) (current.step * 0.3);
                    if (id >= threshold && !next.loaded && !preloading) {
                        triggerPreload(bizTag);
                    }
                    return id;
                }

                // 号段用完，需要切换
                lock.lock();
                try {
                    // 双重检查：可能其他线程已经完成了切换
                    if (current.currentId.get() > current.maxId) {
                        if (next.loaded) {
                            // next 已预加载完毕，直接切换
                            Segment temp = current;
                            current = next;
                            next = temp;
                            next.reset();
                            preloading = false;
                            log.info("[号段] 双Buffer切换成功, bizTag={}", bizTag);
                        } else {
                            // next 未就绪（预加载太慢或未触发），同步加载
                            log.warn("[号段] next未就绪，降级为同步加载, bizTag={}", bizTag);
                            loader.load(bizTag, current);
                            preloading = false;
                        }
                    }
                } finally {
                    lock.unlock();
                }
                // 重新循环获取 ID
            }
        }

        /**
         * 异步预加载下一个号段
         */
        private void triggerPreload(String bizTag) {
            lock.lock();
            try {
                // 双重检查（加锁后再判断一次）
                if (next.loaded || preloading) {
                    return;
                }
                preloading = true;
            } finally {
                lock.unlock();
            }

            preloadExecutor.submit(() -> {
                try {
                    loader.load(bizTag, next);
                    log.info("[号段] 异步预加载完成, bizTag={}", bizTag);
                } catch (Exception e) {
                    log.error("[号段] 异步预加载失败, bizTag={}", bizTag, e);
                    // 预加载失败，重置标记，下次会重试或降级为同步加载
                    preloading = false;
                }
            });
        }
    }
}
