package com.myxhs.search.job;

import com.myxhs.common.response.R;
import com.myxhs.search.feign.OrderFeignClient;
import com.myxhs.search.consumer.SellerSubOrderIndexWriter;
import com.myxhs.search.service.SellerSubOrderIndexDocumentBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 商家子单索引存量回填（C-004 P1）
 * <p>
 * 背景：<b>Canal 只捕增量</b>——索引启用前的存量子单必须回填，否则商家看不到老单
 * （部署顺序：先回填后切流量）。
 * </p>
 * <p>
 * 设计（对齐既有 IndexRebuildJob 口径）：
 * <ul>
 *   <li>游标分页扫描（order 域 Feign，跨全部 16 张物理表）</li>
 *   <li><b>断点续传</b>：整批成功才推进游标（Redis hash）；失败保留断点，可重入补齐</li>
 *   <li>幂等：文档版本 = updated_at 毫秒（与 canal ts 同量级）+ ExternalGte upsert——
 *       与增量事件互不覆盖错误方向（旧数据版本低，新事件版本高）</li>
 *   <li>分布式锁：防多实例并发回填；手动触发走异步（锁的获取/释放在同一线程）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SellerSubOrderIndexBackfillJob {

    static final String STATUS_KEY = "myxhs:search:seller-order-backfill:status";
    static final String LAST_ID_FIELD = "lastId";
    private static final String LOCK_KEY = "myxhs:search:seller-order-backfill:lock";

    private final OrderFeignClient orderFeignClient;
    private final SellerSubOrderIndexWriter indexWriter;
    private final SellerSubOrderIndexDocumentBuilder documentBuilder;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    @Value("${search.seller-order.backfill.batch-size:500}")
    private int batchSize;

    /** 定时回填（断点续传） */
    @Scheduled(cron = "${search.seller-order.backfill.cron:0 30 4 * * ?}")
    public void scheduledBackfill() {
        runWithLock(false);
    }

    /** 手动触发（管理员）：重置断点、全量重跑；异步执行（锁在同线程获取/释放） */
    public void manualBackfill() {
        CompletableFuture.runAsync(() -> runWithLock(true));
    }

    private void runWithLock(boolean resetCheckpoint) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 7200, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[卖家子单回填] 任务执行中，跳过本次触发");
                return;
            }
            if (resetCheckpoint) {
                stringRedisTemplate.delete(STATUS_KEY);
            }
            backfill();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[卖家子单回填] 执行异常（断点已保留，可重入）", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行回填（包可见/公开供单测直呼）。
     *
     * @return 本次写入的文档数
     */
    public int backfill() {
        String lastIdStr = (String) stringRedisTemplate.opsForHash().get(STATUS_KEY, LAST_ID_FIELD);
        long cursor = lastIdStr != null ? Long.parseLong(lastIdStr) : 0L;
        int total = 0;

        while (true) {
            R<List<Map<String, Object>>> resp = orderFeignClient.subOrderScan(cursor, batchSize);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.warn("[卖家子单回填] 扫描失败（保留断点，可重入）: cursor={}", cursor);
                break;
            }
            List<Map<String, Object>> rows = resp.getData();
            if (rows.isEmpty()) {
                break;
            }

            for (Map<String, Object> row : rows) {
                Object subOrderNo = row.get("sub_order_no");
                if (subOrderNo == null || String.valueOf(subOrderNo).isBlank()) {
                    throw new IllegalStateException("回填行缺少 sub_order_no: row=" + row);
                }
                try {
                    // 幂等：版本化 upsert（同 id 重放安全；与 canal 事件版本同量级互不冻结）
                    indexWriter.upsert(String.valueOf(subOrderNo), documentBuilder.build(row),
                            documentBuilder.versionOf(row));
                } catch (Exception e) {
                    // 整批成功才推进断点（对齐既有重建 Job 口径）——失败抛出，断点保留，下次重入补齐
                    throw new IllegalStateException("回填写入失败: subOrderNo=" + subOrderNo, e);
                }
                total++;
            }

            Object lastId = rows.get(rows.size() - 1).get("id");
            cursor = lastId instanceof Number n ? n.longValue() : cursor;
            stringRedisTemplate.opsForHash().put(STATUS_KEY, LAST_ID_FIELD, String.valueOf(cursor));

            if (rows.size() < batchSize) {
                break;
            }
        }
        log.info("[卖家子单回填] 完成: indexed={}, lastId={}", total, cursor);
        return total;
    }
}
