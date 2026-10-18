package com.myxhs.product.job;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.response.R;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.feign.InventoryFeignClient;
import com.myxhs.product.mapper.SkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 库存初始化失败补偿 Job（C-005）
 * <p>
 * 背景：建 SKU 后 afterCommit 调 inventory 初始化库存（Feign）——失败原仅打点 + 管理端手动重试，
 * 无自动补偿（模块对照 01-商品 §7-5）。
 * </p>
 * <p>
 * 机制：失败时 SKU 入 pending set（{@link RedisKeyConstants#PRODUCT_INVENTORY_INIT_PENDING}），
 * 本 Job 周期重试：
 * <ul>
 *   <li>SKU 不存在（已删/脏数据）→ 从队列移除（不再重试）</li>
 *   <li>initStock 成功 或 响应"已初始化"（幂等）→ 移除</li>
 *   <li>仍失败 → 保留（下一轮再试；Redis 不可用时的丢队由 metric + 手动端点兜底）</li>
 * </ul>
 * 多实例：Redis 分布式锁互斥（锁获取/释放同线程）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryInitRepairJob {

    private static final String LOCK_KEY = "myxhs:product:inventory-init:repair-lock";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final SkuMapper skuMapper;
    private final InventoryFeignClient inventoryFeignClient;

    /** 周期重试（默认 5 分钟；可在 myxhs.product.inventory-init.repair-interval-ms 调整） */
    @Scheduled(fixedDelayString = "${myxhs.product.inventory-init.repair-interval-ms:300000}")
    public void scheduledRepair() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 120, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("[库存补偿] 其他实例执行中, 跳过");
                return;
            }
            repair();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[库存补偿] 被中断");
        } catch (Exception e) {
            log.error("[库存补偿] 执行异常(队列保留, 下轮重试)", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行一轮补偿（公开供单测直呼）。
     *
     * @return 本轮成功修复数
     */
    public int repair() {
        Set<String> pending = stringRedisTemplate.opsForSet().members(RedisKeyConstants.PRODUCT_INVENTORY_INIT_PENDING);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int total = pending.size();

        int repaired = 0;
        for (String skuIdStr : pending) {
            Long skuId;
            try {
                skuId = Long.parseLong(skuIdStr);
            } catch (NumberFormatException e) {
                log.warn("[库存补偿] 非法 skuId(移除): {}", skuIdStr);
                remove(skuIdStr);
                continue;
            }

            Sku sku = skuMapper.selectById(skuId);
            if (sku == null) {
                // SKU 已不存在（删除/脏数据）→ 不再重试
                log.info("[库存补偿] SKU 不存在, 移除队列: skuId={}", skuId);
                remove(skuIdStr);
                continue;
            }

            try {
                InventoryFeignClient.InitRequest req = new InventoryFeignClient.InitRequest();
                req.setSkuId(skuId);
                req.setTotalStock(sku.getStock() != null ? sku.getStock() : 0);
                R<Void> r = inventoryFeignClient.initStock(req);
                if (r != null && r.isSuccess()) {
                    remove(skuIdStr);
                    repaired++;
                    log.info("[库存补偿] 重试成功: skuId={}, totalStock={}", skuId, req.getTotalStock());
                } else if (r != null && r.getMessage() != null && r.getMessage().contains("已初始化")) {
                    remove(skuIdStr);
                    log.info("[库存补偿] 已初始化(幂等), 移除队列: skuId={}", skuId);
                } else {
                    log.warn("[库存补偿] 重试仍失败(保留待下轮): skuId={}, resp={}",
                            skuId, r != null ? r.getMessage() : "null");
                }
            } catch (Exception e) {
                log.warn("[库存补偿] 重试调用异常(保留待下轮): skuId={}", skuId, e);
            }
        }
        log.info("[库存补偿] 本轮完成: total={}, repaired={}", total, repaired);
        return repaired;
    }

    private void remove(String skuIdStr) {
        try {
            stringRedisTemplate.opsForSet().remove(RedisKeyConstants.PRODUCT_INVENTORY_INIT_PENDING, skuIdStr);
        } catch (Exception e) {
            log.warn("[库存补偿] 移除队列失败(下轮幂等处理): skuId={}", skuIdStr, e);
        }
    }
}
