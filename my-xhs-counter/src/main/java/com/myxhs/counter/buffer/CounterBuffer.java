package com.myxhs.counter.buffer;

import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.shutdown.GracefulShutdownHook;
import com.myxhs.counter.dto.CounterFlushDTO;
import com.myxhs.counter.mapper.CounterMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Buffer-Trigger 计数缓冲区
 * <p>
 * 核心思想：攒批 → 合并同 Key → 定时/满量刷盘
 * <p>
 * vs 直接写 DB：每次点赞都写 DB → 10000 QPS × 1 次 SQL = 10000 次/秒
 * Buffer-Trigger：10000 次点赞 → 合并后可能只有 2000 个不同 Key → 1 次批量 SQL
 * <p>
 * 触发条件（先到先触发）：
 * 1. 满量触发：Buffer 中累计 100 条写入
 * 2. 定时触发：每 5 秒检查一次
 * <p>
 * 合并策略：同一个 Key 的增减合并（+1, +1, -1 → +1），4 次写变 1 次
 * <p>
 * 双 Buffer 交换方案：flush 时将当前 buffer 换出，立即创建新 buffer 接收后续写入，
 * 避免 flush 期间 add 写入的数据被 clear 丢失。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterBuffer implements GracefulShutdownHook {

    private final CounterMapper counterMapper;
    private final IdGeneratorUtil idGeneratorUtil;

    /**
     * 缓冲区：Key = "targetType:targetId:countType", Value = 累计增量
     * <p>
     * 非 final，因为 flush 时采用双 Buffer 交换方案。
     * volatile 保证 add() 和 flush() 之间的可见性。
     * </p>
     */
    private volatile ConcurrentHashMap<String, AtomicLong> buffer = new ConcurrentHashMap<>();

    /** 缓冲区写入计数（用于满量触发判断） */
    private final AtomicInteger bufferSize = new AtomicInteger(0);

    /** 满 100 条触发刷盘 */
    private static final int MAX_BUFFER_SIZE = 100;

    /** 最大重试次数 */
    private static final int MAX_RETRY = 3;

    /** 批量 Upsert 每批大小（防止单次 SQL 过大） */
    private static final int BATCH_SIZE = 500;

    /** 刷盘锁（替代 synchronized，支持 tryLock 非阻塞，避免高并发下写入线程被阻塞） */
    private final ReentrantLock flushLock = new ReentrantLock();

    /**
     * 写入缓冲区
     * <p>
     * 线程安全：ConcurrentHashMap + AtomicLong 保证并发写入正确性。
     * 合并同 Key 增减：+1, +1, -1 → +1（4 次写变 1 次 SQL）
     * </p>
     *
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @param countType  计数类型
     * @param delta      增量（+1 或 -1）
     */
    public void add(int targetType, long targetId, int countType, long delta) {
        String key = targetType + ":" + targetId + ":" + countType;

        // 跨代写防护：compute 在旧 buffer 上执行后 buffer 被 doFlush 交换 → 撤销并重试到新 buffer
        while (true) {
            ConcurrentHashMap<String, AtomicLong> current = buffer;
            current.compute(key, (k, v) -> {
                if (v == null) v = new AtomicLong(0);
                v.addAndGet(delta);
                return v;
            });
            // buffer 未被交换 → 写入成功，退出
            if (current == buffer) break;
            // buffer 已被交换 → 撤销旧 buffer 上的写入，重试到新 buffer
            current.compute(key, (k, v) -> {
                if (v != null) {
                    v.addAndGet(-delta);
                    if (v.get() == 0) return null;
                    return v;
                }
                return null;
            });
        }

        // 检查是否触发满量刷盘
        if (bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
            flush();
        }
    }

    /**
     * 定时刷盘（每 5 秒）
     * <p>
     * 保留 @Scheduled 的原因：
     * - 高频任务（5 秒），不适合 XXL-Job 调度（调度开销大于执行时间）
     * - 每个实例刷自己的 buffer（ConcurrentHashMap），不存在多实例竞争问题
     * - 如果迁移到 XXL-Job，Admin 每 5 秒调度一次压力大，且只有一个实例刷盘会导致其他实例 buffer 积压
     * </p>
     */
    @Scheduled(fixedRate = 5000)
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    /**
     * 刷盘：合并后批量写 DB
     * <p>
     * 采用双 Buffer 交换方案：
     * 1. 将当前 buffer 换出（snapshot）
     * 2. 立即创建新 buffer 接收后续写入
     * 3. 对 snapshot 进行合并、过滤、排序、批量写 DB
     * <p>
     * 使用 ReentrantLock.tryLock() 非阻塞方式：
     * - 获取锁成功 → 执行刷盘
     * - 获取锁失败 → 说明其他线程正在刷盘，直接跳过（下次定时任务或满量触发会处理）
     * 相比 synchronized 的优势：不会阻塞写入线程（add 方法），避免高并发下写入延迟。
     * </p>
     */
    private void flush() {
        // tryLock 非阻塞：获取不到锁说明其他线程正在刷盘，直接跳过
        if (!flushLock.tryLock()) {
            log.debug("[Buffer-Trigger] 其他线程正在刷盘，跳过本次");
            return;
        }
        try {
            doFlush();
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * 实际刷盘逻辑（已持有锁）
     */
    private void doFlush() {
        if (buffer.isEmpty()) return;

        // 1. 双 Buffer 交换
        ConcurrentHashMap<String, AtomicLong> snapshot = buffer;
        buffer = new ConcurrentHashMap<>();
        bufferSize.set(0);

        if (snapshot.isEmpty()) return;

        // 2. 从快照中提取非零增量（合并后为 0 的跳过，如 +1 再 -1）
        Map<String, Long> deltaMap = new HashMap<>();
        snapshot.forEach((key, value) -> {
            long delta = value.get();
            if (delta != 0) {
                deltaMap.put(key, delta);
            }
        });

        if (deltaMap.isEmpty()) {
            log.debug("[Buffer-Trigger] 合并后全部为 0，跳过刷盘");
            return;
        }

        // 3. 构建刷盘参数列表
        List<CounterFlushDTO> flushList = deltaMap.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split(":");
                    return new CounterFlushDTO(
                            idGeneratorUtil.nextId(),
                            Integer.parseInt(parts[0]),
                            Long.parseLong(parts[1]),
                            Integer.parseInt(parts[2]),
                            entry.getValue());
                })
                .collect(Collectors.toList());

        // 4. 按唯一索引排序，避免死锁（多个事务交叉加锁导致死锁）
        flushList.sort(Comparator.comparing(CounterFlushDTO::getTargetType)
                .thenComparing(CounterFlushDTO::getTargetId)
                .thenComparing(CounterFlushDTO::getCountType));

        // 5. 分批写入 DB
        for (int i = 0; i < flushList.size(); i += BATCH_SIZE) {
            List<CounterFlushDTO> batch = flushList.subList(i, Math.min(i + BATCH_SIZE, flushList.size()));
            try {
                counterMapper.batchUpsert(batch);
                log.info("[Buffer-Trigger] 刷盘成功: {} 条", batch.size());
            } catch (Exception e) {
                log.error("[Buffer-Trigger] 刷盘失败: {} 条", batch.size(), e);
                retryFlush(batch);
            }
        }
    }

    /**
     * 重试刷盘（最多 3 次）
     * <p>
     * 重试仍失败则记录日志，由每天凌晨的对账修复任务兜底。
     * </p>
     */
    private void retryFlush(List<CounterFlushDTO> batch) {
        for (int i = 1; i <= MAX_RETRY; i++) {
            try {
                Thread.sleep(100L * i); // 递增退避
                counterMapper.batchUpsert(batch);
                log.info("[Buffer-Trigger] 重试第 {} 次成功: {} 条", i, batch.size());
                return;
            } catch (Exception e) {
                log.error("[Buffer-Trigger] 重试第 {} 次失败: {} 条", i, batch.size(), e);
            }
        }
        // 重试全部失败，记录日志（对账修复兜底）
        log.error("[Buffer-Trigger] 重试 {} 次全部失败，等待对账修复。数据: {}", MAX_RETRY,
                batch.stream().map(dto -> dto.getTargetType() + ":" + dto.getTargetId() + ":" + dto.getCountType() + "=" + dto.getDelta())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * 优雅停机：JVM 关闭前强制刷盘，尽量减少数据丢失
     * <p>
     * 停机时使用 lock()（阻塞等待），而非 tryLock()，确保刷盘一定执行。
     * 如果其他线程正在刷盘，等待其完成后再执行一次（处理等待期间新写入的数据）。
     * </p>
     */
    @PreDestroy
    public void shutdown() {
        log.info("[Buffer-Trigger] 优雅停机，强制刷盘...");
        flushLock.lock();
        try {
            doFlush();
        } finally {
            flushLock.unlock();
        }
        log.info("[Buffer-Trigger] 优雅停机刷盘完成");
    }

    @Override
    public void onShutdown() {
        // 由 GracefulShutdownListener 在 Nacos 注销后主动调用，确保执行顺序
        shutdown();
    }
}
