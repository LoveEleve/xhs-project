package com.myxhs.inventory.job;

import com.myxhs.inventory.mapper.InventoryMapper;
import com.myxhs.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 库存补偿重试任务
 * <p>
 * 扫描 t_inventory_compensation 表中未处理的回滚失败记录，
 * 使用 release.lua 重试回退库存。最多重试 3 次，超出标记为死信。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryCompensationJob {

    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final RedissonClient redissonClient;
    private final com.myxhs.common.metrics.BusinessMetrics businessMetrics; // C-008 F9：指标

    private static final String LOCK_KEY = "myxhs:lock:job:inventory:compensation"; // C-008 F10：前缀统一
    /** 补偿类型：1-预扣回滚 2-退款回补 */
    private static final int COMPENSATION_TYPE_REFUND_RESTORE = 2;

    private static final int MAX_RETRY = 3;
    private static final int BATCH_SIZE = 50;

    /** C-008：单键家族（KEYS 同 {skuId} tag） */
    private static final String STOCK_KEY_TPL = "inventory:{%d}:stock";
    private static final String PREDEDUCT_KEY_TPL = "inventory:{%d}:prededuct:%d";
    private static final String INDEX_KEY_TPL = "inventory:{%d}:prededuct:index";

    private static String stockKey(Long skuId) { return String.format(STOCK_KEY_TPL, skuId); }
    private static String predeductKey(Long skuId, Long orderId) { return String.format(PREDEDUCT_KEY_TPL, skuId, orderId); }
    private static String indexKey(Long skuId) { return String.format(INDEX_KEY_TPL, skuId); }

    @Scheduled(fixedRate = 30000)
    public void retryCompensations() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 25, TimeUnit.SECONDS);
            if (!acquired) return;

            doRetryCompensations();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doRetryCompensations() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(30);
        List<Map<String, Object>> records = inventoryMapper.selectPendingCompensation(cutoff, MAX_RETRY, BATCH_SIZE);
        for (Map<String, Object> row : records) {
            Long id = ((Number) row.get("id")).longValue();
            Long orderId = ((Number) row.get("order_id")).longValue();
            Long skuId = ((Number) row.get("sku_id")).longValue();

            int type = row.get("type") == null ? 1 : ((Number) row.get("type")).intValue();
            try {
                if (type == COMPENSATION_TYPE_REFUND_RESTORE) {
                    // 退款回补补偿：调用 refundRestore（累计正增量幂等，重复重试安全）
                    Long userId = row.get("user_id") == null ? null : ((Number) row.get("user_id")).longValue();
                    int quantity = ((Number) row.get("quantity")).intValue();
                    var req = new com.myxhs.inventory.dto.request.RefundRestoreRequest();
                    req.setOrderId(orderId);
                    req.setSkuId(skuId);
                    req.setQuantity(quantity);
                    req.setUserId(userId);
                    inventoryService.refundRestore(req);
                    inventoryMapper.markCompensationResolved(id);
                    log.info("[库存补偿] 退款回补重试成功: orderId={}, skuId={}, qty={}", orderId, skuId, quantity);
                    continue;
                }
                // C-008：单键家族——按 (sku, orderId) 原子回退（原 F-037 来源桶读取随分桶移除；
                // release.lua 原子完成：回退库存 + 删记录 + 索引移除）
                Long result = stringRedisTemplate.execute(
                        releaseScript,
                        List.of(stockKey(skuId), predeductKey(skuId, orderId), indexKey(skuId)),
                        String.valueOf(orderId));

                if (result != null && result > 0) {
                    inventoryMapper.markCompensationResolved(id);
                    businessMetrics.recordInventoryAction("compensation_retry_ok"); // C-008 F9
                    log.info("[库存补偿] 回退成功: orderId={}, skuId={}, qty={}", orderId, skuId, result);
                } else {
                    inventoryMapper.incrementCompensationRetry(id, MAX_RETRY);
                    log.warn("[库存补偿] 回退失败(预扣记录已不存在): orderId={}, skuId={}", orderId, skuId);
                }
            } catch (Exception e) {
                inventoryMapper.incrementCompensationRetry(id, MAX_RETRY);
                log.error("[库存补偿] 重试异常: orderId={}, skuId={}", orderId, skuId, e);
            }
        }
    }
}
