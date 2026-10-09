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
    private final org.redisson.api.RedissonClient redissonClient;

    private static final String TOTAL_KEY_TPL = "inventory:{%d}:total";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_TPL, skuId); }

    /** 分桶对账 Lua 脚本（原子求和+对比设置） */
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> reconcileBucketsScript;

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
    public int doReconcile() {
        // 手动端点与 XXL 调度可能重叠：抢不到锁直接跳过本轮（原实现无锁，全量对账并发跑会互相覆盖）
        org.redisson.api.RLock lock = redissonClient.getLock("myxhs:lock:job:inventory:reconcile");
        if (!lock.tryLock()) {
            log.info("[库存对账] 已有实例在执行，跳过本轮");
            return 0;
        }
        try {
        log.info("[库存对账] 开始执行...");
        long startTime = System.currentTimeMillis();
        int repairCount = 0;

        List<Inventory> inventories = inventoryMapper.selectList(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getDeleted, 0));
        log.info("[库存对账] 待对账SKU数: {}", inventories.size());

        for (Inventory inventory : inventories) {
            String totalKey = totalKey(inventory.getSkuId());
            String redisTotalStr = stringRedisTemplate.opsForValue().get(totalKey);

            if (redisTotalStr == null) {
                log.debug("[库存对账] Redis未初始化: skuId={}", inventory.getSkuId());
                continue;
            }

            int redisTotal;
            try {
                redisTotal = Integer.parseInt(redisTotalStr);
            } catch (NumberFormatException e) {
                log.warn("[库存对账] 跳过脏数据(total非数字): skuId={}, value={}", inventory.getSkuId(), redisTotalStr);
                continue;
            }
            int mysqlAvailable = inventory.getAvailableStock();

            if (redisTotal != mysqlAvailable) {
                int oldAvailable = mysqlAvailable;
                // 目标 UPDATE 只更新 available_stock：不用 updateById 全字段盲写
                // （selectList 快照的 locked_stock 可能是旧值，盲写会回滚并发 L2 的 locked 变更 → 幻影锁复活）
                inventoryMapper.updateAvailableStockOnly(inventory.getSkuId(), redisTotal);
                repairCount++;
                log.info("[库存对账] 修复: skuId={}, mysql: {}→{} (以Redis为准)",
                        inventory.getSkuId(), oldAvailable, redisTotal);
            }

            // 【M9】分桶完整性检查：各桶之和 == 总库存
            reconcileBuckets(inventory.getSkuId());
        }

        // 【新增】locked_stock 对账：以 Redis 在途预扣（inventory:prededuct:* Hash）为权威重算。
        // 修复缺口：预扣记录丢失/TTL 过期/确认失败都会留下"幽灵锁"，而 reinitStock 会把 locked 计入总库存
        // → 可售虚增。方向保守：预扣记录不存在即视为 0（宁可少算可售，不会多卖）。
        repairCount += reconcileLockedStock(inventories);

        // 清理 7 天前的预扣幂等占位（原实现成功预扣的占位永久保留 → 表线性增长；
        // 幂等只需覆盖"预扣 TTL + MQ 重投窗口"，7 天后无重投可能）
        try {
            int purged = inventoryMapper.deleteStalePredeductIdem(
                    java.time.LocalDateTime.now().minusDays(7), 2000);
            if (purged > 0) {
                log.info("[库存对账] 清理过期预扣幂等占位 {} 行", purged);
            }
        } catch (Exception e) {
            log.warn("[库存对账] 清理过期预扣幂等占位失败(下轮重试)", e);
        }

        // outbox（7 天）/ 补偿表（90 天）保留：同样日切一次 + 有界排空
        purgeTablesDaily();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[库存对账] 完成: 对账{}个SKU, 修复{}条, 耗时{}ms",
                inventories.size(), repairCount, elapsed);

        return repairCount;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * locked_stock 对账：扫描在途预扣记录（按 orderId 的 Hash，TTL 内即"未决"），
     * 按 SKU 汇总后与 MySQL locked_stock 比对并修正。
     * <p>
     * 扫描量有界：预扣 Hash 的 TTL=预扣有效期（1800s），因此 key 数 ≈ 在途订单数；
     * 每小时一轮，成本可控。方向保守：预扣记录缺失即视为 0（宁可少算 locked，不会多卖）；
     * 注意：SCAN 在 Redis Cluster 下只覆盖单个节点，若未来上 Cluster 需改为逐节点扫描（当前单实例+Sentinel 无影响）；
     * 若 L2 事件滞后于本次扫描，locked 会在事件到达后被再次抬高，最多一个事件的量，下轮对账收敛。
     * @return 修复条数
     * </p>
     */
    private int reconcileLockedStock(List<Inventory> all) {
        java.util.Map<Long, Integer> lockedBySku = new java.util.HashMap<>();
        try (var cursor = stringRedisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("inventory:prededuct:*").count(500).build())) {
            cursor.forEachRemaining(key -> {
                if (key.endsWith(":index")) {
                    return;   // inventory:prededuct:index 是 ZSet 索引，不是预扣 Hash
                }
                try {
                    java.util.Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
                    for (java.util.Map.Entry<Object, Object> e : entries.entrySet()) {
                        String field = String.valueOf(e.getKey());
                        if (field.endsWith(":bucket")) {
                            continue;   // 桶号字段不是数量
                        }
                        Long skuId = Long.valueOf(field);
                        int qty = Integer.parseInt(String.valueOf(e.getValue()));
                        lockedBySku.merge(skuId, qty, Integer::sum);
                    }
                } catch (Exception ex) {
                    log.warn("[库存对账] 预扣记录解析失败(跳过): key={}", key, ex);
                }
            });
        } catch (Exception e) {
            log.error("[库存对账] 在途预扣扫描失败, 本轮跳过 locked 对账", e);
            return 0;
        }

        int repaired = 0;
        for (Inventory inventory : all) {
            int expected = lockedBySku.getOrDefault(inventory.getSkuId(), 0);
            int current = inventory.getLockedStock() == null ? 0 : inventory.getLockedStock();
            if (current != expected) {
                inventoryMapper.updateLockedStockOnly(inventory.getSkuId(), expected);
                repaired++;
                log.info("[库存对账] locked 修复: skuId={}, {}→{}（以在途预扣为准）",
                        inventory.getSkuId(), current, expected);
            }
        }
        log.info("[库存对账] locked 对账完成: 在途SKU数={}, 修复={}", lockedBySku.size(), repaired);
        return repaired;
    }

    /**
     * 【M9】分桶完整性对账：各桶库存之和 == 总库存
     * 不一致时以分桶之和为准修正总库存。
     * <p>
     * 【原子化修复】使用 Lua 脚本在单个原子执行单位内完成求和+对比+设置，
     * 替代 Java 侧"读 total → 循环 GET 各桶 → SET"的非原子序列——
     * 读后发生的 preDeduct/release 会被盲 SET 覆盖（幻影回滚在途预扣）。
     * </p>
     */
    private void reconcileBuckets(Long skuId) {
        String countStr = stringRedisTemplate.opsForValue()
                .get(String.format("inventory:bucket:count:{%d}", skuId));
        if (countStr == null) return;
        int bucketCount;
        try {
            bucketCount = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            log.warn("[库存对账] 跳过脏数据(bucketCount非数字): skuId={}, value={}", skuId, countStr);
            return;
        }

        // 构建 KEYS：[totalKey, bucket:0, bucket:1, ..., bucket:N-1]
        List<String> keys = new java.util.ArrayList<>(1 + bucketCount);
        keys.add(String.format("inventory:{%d}:total", skuId));
        for (int i = 0; i < bucketCount; i++) {
            keys.add(String.format("inventory:{%d}:bucket:", skuId) + i);
        }

        Long fixed = stringRedisTemplate.execute(reconcileBucketsScript, keys);
        if (fixed != null && fixed == 1) {
            log.warn("[库存对账] 分桶总量不一致已修正(原子Lua): skuId={}", skuId);
        }
    }
    /**
     * 库存 outbox / 补偿表保留策略（日切一次，有界排空）
     */
    private void purgeTablesDaily() {
        try {
            String marker = "myxhs:inventory:cleanup:" + java.time.LocalDate.now();
            boolean first = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(marker, "1", java.time.Duration.ofHours(25)));
            if (!first) {
                return;
            }
            int outbox = 0;
            for (int i = 0; i < 10; i++) {
                int n = inventoryMapper.deleteSentOutbox(java.time.LocalDateTime.now().minusDays(7), 2000);
                outbox += n;
                if (n < 2000) {
                    break;
                }
            }
            int comp = 0;
            for (int i = 0; i < 10; i++) {
                int n = inventoryMapper.deleteSettledCompensation(java.time.LocalDateTime.now().minusDays(90), 2000);
                comp += n;
                if (n < 2000) {
                    break;
                }
            }
            if (outbox + comp > 0) {
                log.info("[库存对账] 保留策略清理: outbox(7d)={} 条, compensation(90d)={} 条", outbox, comp);
            }
        } catch (Exception e) {
            log.error("[库存对账] 保留策略清理失败(明日重试)", e);
        }
    }

}
