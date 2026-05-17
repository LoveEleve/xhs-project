package com.myxhs.common.datagen;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 多线程批量写入执行器
 * <p>
 * 核心能力：
 * 1. 多线程并行写入（线程数 = CPU 核数 × 2）
 * 2. JDBC Batch 批量提交（每批 5000 条）
 * 3. 实时进度条（已写入/总量/速度/预估剩余时间）
 * 4. 按 ID 范围分片（每个线程负责一段 ID 范围，互不冲突）
 * </p>
 * <p>
 * 使用方式：
 * <pre>
 * BatchInsertExecutor executor = new BatchInsertExecutor(dataSource);
 * executor.execute(
 *     "t_user",                          // 表名（用于日志）
 *     10_000_000,                         // 总数据量
 *     "INSERT INTO t_user (id, nickname, phone, ...) VALUES (?, ?, ?, ...)",
 *     (ps, id) -> {                       // 参数填充器
 *         ps.setLong(1, id);
 *         ps.setString(2, "user_" + id);
 *         ps.setString(3, RandomDataFactory.phone());
 *     }
 * );
 * </pre>
 * </p>
 * <p>
 * 关键参数：
 * - 线程数：CPU 核数 × 2（IO 密集型）
 * - 批次大小：5000 条/批（太大会导致事务超时）
 * - 提交策略：每批提交一次（不要整个线程一个事务）
 * - JDBC URL 必须包含 rewriteBatchedStatements=true
 * </p>
 */
@Slf4j
public class BatchInsertExecutor {

    private final DataSource dataSource;

    /** 批次大小：每批提交的记录数 */
    private static final int BATCH_SIZE = 5000;

    /** 进度打印间隔（毫秒） */
    private static final long PROGRESS_INTERVAL_MS = 3000;

    /** 已写入的总记录数（所有线程共享） */
    private final AtomicLong totalInserted = new AtomicLong(0);

    public BatchInsertExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行批量写入
     *
     * @param tableName     表名（用于日志显示）
     * @param totalCount    总数据量
     * @param sql           INSERT SQL（带 ? 占位符）
     * @param paramSetter   参数填充器：(PreparedStatement, 当前ID) → 设置参数
     */
    public void execute(String tableName, long totalCount, String sql,
                        BiConsumer<PreparedStatement, Long> paramSetter) {
        int threadCount = Runtime.getRuntime().availableProcessors() * 2;
        threadCount = Math.max(2, Math.min(threadCount, 16)); // 限制 2~16 线程

        log.info("========================================");
        log.info("[数据生成] 开始写入 {} | 总量: {} | 线程数: {} | 批次: {}",
                tableName, formatNumber(totalCount), threadCount, BATCH_SIZE);
        log.info("========================================");

        totalInserted.set(0);
        long startTime = System.currentTimeMillis();

        // 按 ID 范围分片
        long chunkSize = totalCount / threadCount;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r);
            t.setName("datagen-" + tableName);
            t.setDaemon(true);
            return t;
        });

        // 启动进度打印线程
        ScheduledExecutorService progressPrinter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datagen-progress");
            t.setDaemon(true);
            return t;
        });
        progressPrinter.scheduleAtFixedRate(
                () -> printProgress(tableName, totalCount, startTime),
                PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 提交分片任务
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            long startId = i * chunkSize + 1;
            long endId = (i == threadCount - 1) ? totalCount : (i + 1) * chunkSize;
            futures.add(pool.submit(() -> insertRange(sql, paramSetter, startId, endId)));
        }

        // 等待所有线程完成
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[数据生成] {} 被中断", tableName, e);
            } catch (ExecutionException e) {
                log.error("[数据生成] {} 执行异常", tableName, e.getCause());
            }
        }

        // 关闭线程池
        pool.shutdown();
        progressPrinter.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        long inserted = totalInserted.get();
        double speed = inserted * 1000.0 / Math.max(elapsed, 1);

        log.info("========================================");
        log.info("[数据生成] {} 完成 | 写入: {} | 耗时: {}s | 速度: {}/s",
                tableName, formatNumber(inserted), elapsed / 1000, formatNumber((long) speed));
        log.info("========================================");
    }

    /**
     * 写入指定 ID 范围的数据
     */
    private void insertRange(String sql, BiConsumer<PreparedStatement, Long> paramSetter,
                             long startId, long endId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batchCount = 0;
                for (long id = startId; id <= endId; id++) {
                    try {
                        paramSetter.accept(ps, id);
                        ps.addBatch();
                        batchCount++;

                        if (batchCount >= BATCH_SIZE) {
                            ps.executeBatch();
                            conn.commit();
                            totalInserted.addAndGet(batchCount);
                            batchCount = 0;
                        }
                    } catch (Exception e) {
                        log.warn("[数据生成] 跳过 id={}, 原因: {}", id, e.getMessage());
                    }
                }
                // 提交剩余数据
                if (batchCount > 0) {
                    ps.executeBatch();
                    conn.commit();
                    totalInserted.addAndGet(batchCount);
                }
            }
        } catch (SQLException e) {
            log.error("[数据生成] 数据库写入异常, range=[{}, {}]", startId, endId, e);
        }
    }

    /**
     * 打印进度
     */
    private void printProgress(String tableName, long totalCount, long startTime) {
        long inserted = totalInserted.get();
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = inserted * 1000.0 / Math.max(elapsed, 1);
        double percent = inserted * 100.0 / totalCount;
        long remaining = speed > 0 ? (long) ((totalCount - inserted) / speed) : 0;

        log.info("[数据生成] {} | 进度: {}/{} ({}%) | 速度: {}/s | 剩余: {}s",
                tableName, formatNumber(inserted), formatNumber(totalCount),
                String.format("%.1f", percent), formatNumber((long) speed), remaining);
    }

    /**
     * 格式化数字（加千分位分隔符）
     */
    static String formatNumber(long number) {
        if (number < 1000) return String.valueOf(number);
        if (number < 1_000_000) return String.format("%.1fK", number / 1000.0);
        if (number < 1_000_000_000) return String.format("%.1fM", number / 1_000_000.0);
        return String.format("%.1fB", number / 1_000_000_000.0);
    }
}
