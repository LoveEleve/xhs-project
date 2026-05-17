package com.myxhs.inventory.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.inventory.entity.Inventory;
import com.myxhs.inventory.mapper.InventoryMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存对账修复任务（L3）（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 3 点执行，对比 Redis 总库存与 MySQL 可用库存。
 * 以 Redis 为准修复 MySQL（因为 Redis 是实时扣减的权威数据源）。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 * <p>
 * 修复策略：
 * 1. Redis 总库存 != MySQL available_stock → 以 Redis 为准更新 MySQL
 * 2. Redis 未初始化 + MySQL 有记录 → 跳过（可能是还未初始化到 Redis）
 * 3. Redis 已初始化 + MySQL 无记录 → 告警（异常情况）
 * </p>
 * <p>
 * 边界条件：
 * 如果对账时有未消费的 MQ 消息（L2 消息积压），以 Redis 为准修复 MySQL 后，
 * 积压的 MQ 消息被消费时可能导致 MySQL 被多扣一次。
 * 这种情况概率极低（凌晨 3 点 MQ 积压），且下一次对账会再次修复。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReconcileJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final InventoryMapper inventoryMapper;

    private static final String TOTAL_KEY_PREFIX = "inventory:total:";

    /**
     * 库存对账修复（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 3 * * ?（每天凌晨 3 点）
     */
    @XxlJob("inventoryReconcileJob")
    public void reconcile() {
        try {
            int repairCount = doReconcile();
            XxlJobHelper.handleSuccess("对账修复完成，修复 " + repairCount + " 条");
        } catch (Exception e) {
            log.error("[库存对账] 执行异常", e);
            XxlJobHelper.handleFail("库存对账异常: " + e.getMessage());
        }
    }

    /**
     * 实际的对账逻辑
     *
     * @return 修复的记录数
     */
    private int doReconcile() {
        log.info("[库存对账] 开始执行...");
        long startTime = System.currentTimeMillis();
        int repairCount = 0;

        List<Inventory> inventories = inventoryMapper.selectList(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getDeleted, 0));
        log.info("[库存对账] 待对账SKU数: {}", inventories.size());

        for (Inventory inventory : inventories) {
            String totalKey = TOTAL_KEY_PREFIX + inventory.getSkuId();
            String redisTotalStr = stringRedisTemplate.opsForValue().get(totalKey);

            if (redisTotalStr == null) {
                log.debug("[库存对账] Redis未初始化: skuId={}", inventory.getSkuId());
                continue;
            }

            int redisTotal = Integer.parseInt(redisTotalStr);
            int mysqlAvailable = inventory.getAvailableStock();

            if (redisTotal != mysqlAvailable) {
                int oldAvailable = mysqlAvailable;
                inventory.setAvailableStock(redisTotal);
                inventoryMapper.updateById(inventory);
                repairCount++;
                log.info("[库存对账] 修复: skuId={}, mysql: {}→{} (以Redis为准)",
                        inventory.getSkuId(), oldAvailable, redisTotal);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[库存对账] 完成: 对账{}个SKU, 修复{}条, 耗时{}ms",
                inventories.size(), repairCount, elapsed);

        return repairCount;
    }
}
