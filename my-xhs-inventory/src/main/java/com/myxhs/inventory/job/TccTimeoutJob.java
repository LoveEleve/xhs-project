package com.myxhs.inventory.job;

import com.myxhs.common.tcc.TccFenceService;
import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TCC 超时自动 Cancel 任务（xid 维度精确取消）
 * <p>
 * 扫描 t_tcc_freeze_detail 中 status=1（已冻结）且创建时间超过阈值的记录，
 * 按 xid 逐个取消。替代旧实现（按 SKU 聚合 freezing_stock + updated_at 一刀切）的缺陷：
 * 1. 聚合 freezing_stock 无 xid 维度 → 一刀切回退该 SKU 全部冻结（含别单刚冻的）
 * 2. updated_at 被任何冻结刷新 → 热 SKU 超时永远不可达
 * 3. 回退不写 fence → 迟到 Confirm 把已回退库存再 confirm → 超卖
 * </p>
 * <p>
 * 正确性保证：
 * - 每条冻结明细有独立 created_at，互不影响（热 SKU 的新冻结不会被误取消）
 * - 取消前经 TccFenceService.cancelFence 状态转换（1→3），
 *   迟到 Confirm 会被 fence 拒绝（status=3）→ 不会把已回退库存再 confirm
 * - 明细状态条件更新（status 1→3）防止并发重复解冻凭空加库存
 * </p>
 * <p>
 * 分布式安全：Redisson 分布式锁保证多实例只有一个执行。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TccTimeoutJob {

    private final InventoryMapper inventoryMapper;
    private final TccFenceService tccFenceService;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:job:inventory:tcc-timeout";
    private static final int TIMEOUT_MINUTES = 10;
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedRate = 60000)
    public void cancelExpiredFreezes() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 50, TimeUnit.SECONDS);
            if (!acquired) return;

            doCancelExpiredFreezes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doCancelExpiredFreezes() {
        LocalDateTime cutoff = LocalDateTime.now().minus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<Map<String, Object>> expiredDetails = inventoryMapper.selectExpiredFreezeDetails(cutoff, BATCH_SIZE);

        for (Map<String, Object> detail : expiredDetails) {
            String xid = (String) detail.get("xid");
            Long branchId = ((Number) detail.get("branch_id")).longValue();
            Long skuId = ((Number) detail.get("sku_id")).longValue();
            int quantity = ((Number) detail.get("quantity")).intValue();

            try {
                // 经 fence 状态转换：仅 1→3 转换成功才解冻（防并发重复解冻 + 迟到 Confirm 被拒）
                TccFenceService.CancelFenceResult fenceResult =
                        tccFenceService.cancelFence(xid, branchId, "tryDeductStock");
                if (fenceResult == TccFenceService.CancelFenceResult.EXECUTE) {
                    int affected = inventoryMapper.cancelFreeze(skuId, quantity);
                    if (affected > 0) {
                        log.warn("[TCC超时] 自动解冻: xid={}, branchId={}, skuId={}, qty={}",
                                xid, branchId, skuId, quantity);
                    }
                    // 明细状态条件更新（status 1→3），并发下只有一个成功
                    inventoryMapper.updateFreezeDetailStatus(xid, branchId, skuId, 1, 3);
                } else if (fenceResult == TccFenceService.CancelFenceResult.REJECTED_CONFIRMED) {
                    // fence 已 Confirm：明细状态同步为 2（不再扫描该记录）
                    inventoryMapper.updateFreezeDetailStatus(xid, branchId, skuId, 1, 2);
                    log.debug("[TCC超时] 明细已确认(同步状态): xid={}, skuId={}", xid, skuId);
                } else {
                    // fence 已 Cancel（空回滚/重复）：明细状态同步为 3，不重复解冻
                    inventoryMapper.updateFreezeDetailStatus(xid, branchId, skuId, 1, 3);
                    log.debug("[TCC超时] 明细已取消(同步状态): xid={}, skuId={}", xid, skuId);
                }
            } catch (Exception e) {
                log.error("[TCC超时] 解冻失败: xid={}, skuId={}", xid, skuId, e);
            }
        }
    }
}
