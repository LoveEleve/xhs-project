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
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:job:inventory:compensation";
    private static final int MAX_RETRY = 3;
    private static final int BATCH_SIZE = 50;

    private static final String TOTAL_KEY_TPL = "inventory:{%d}:total";
    private static final String BUCKET_KEY_TPL = "inventory:{%d}:bucket:";
    /** T-063（2026-08-14）：release.lua 需要 KEYS[4]=index——原补偿调用缺省，预扣 Hash 清空时 ZREM nil 脚本错 */
    private static final String PREDEDUCT_INDEX_KEY = "inventory:prededuct:index";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_TPL, skuId); }
    private static String bucketKey(Long skuId, int bucketNo) { return String.format(BUCKET_KEY_TPL, skuId) + bucketNo; }

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

            try {
                String predeductKey = "inventory:prededuct:" + orderId;
                // 【F-037】从预扣 hash 读取实际来源桶号，而非固定 bucket 0
                Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, skuId + ":bucket");
                int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;
                // 使用 release.lua 重试回退（T-063：补齐 KEYS[4]=index；T-065：补齐 ARGV[2]=orderId——
                // 原实现仅传 skuId，ZREM index 时 ARGV[2]=nil 脚本错 → total 已回退但标记失败 → 下周期重复回退超发）
                Long result = stringRedisTemplate.execute(
                        releaseScript,
                        List.of(totalKey(skuId), predeductKey, bucketKey(skuId, bucketNo), PREDEDUCT_INDEX_KEY),
                        String.valueOf(skuId), String.valueOf(orderId));

                if (result != null && result > 0) {
                    inventoryMapper.markCompensationResolved(id);
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
