package com.myxhs.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券库存对账修复任务（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 2 点执行，对比 Redis 库存与 MySQL remain_count。
 * 以 Redis 为准修复 MySQL（因为 Redis 是实时扣减的权威数据源，
 * claimCoupon 通过 Lua 原子扣减 Redis 库存，MySQL 异步持久）。
 * </p>
 * <p>
 * 为什么需要对账？
 * 1. 退券时 MySQL 已提交但 Redis 操作可能失败 → Redis stock 比 MySQL remain_count 少
 * 2. MQ 回滚失败 → Redis stock 比 MySQL remain_count 多
 * 3. Redis 重启后数据丢失 → Redis stock 为 0 或未初始化
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponReconcileJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final CouponTemplateMapper templateMapper;
    private final com.myxhs.coupon.mapper.UserCouponMapper userCouponMapper;
    private final com.myxhs.coupon.mapper.CouponOutboxMapper outboxMapper;
    private final com.myxhs.common.metrics.BusinessMetrics businessMetrics;

    private static final String STOCK_KEY_TPL = "myxhs:coupon:{%d}:stock";
    private static final Duration TEMPLATE_CACHE_TTL = Duration.ofSeconds(1800);

    /**
     * 限领计数对账：SCAN 所有 claimed 计数键，与该 (用户, 模板) 的实际发券数比对并修正。
     * <p>
     * 语义：claimed 记录"已领次数"（含已使用/已过期券），因此以 t_user_coupon 的非删行数为准；
     * 在途（Outbox 已写、消费未到）的领取会让计数偏大 1，日切低峰执行风险可接受；
     * 注意：SCAN 在 Redis Cluster 下只覆盖单节点（当前单实例+Sentinel 无影响，上 Cluster 需逐节点扫描）。
     * </p>
     */
    private int reconcileClaimedCounters() {
        int repaired = 0;
        long scanned = 0;
        try (var cursor = stringRedisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("myxhs:coupon:*:claimed:*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                scanned++;
                try {
                    // key = myxhs:coupon:{templateId}:claimed:{userId}
                    int l = key.indexOf('{');
                    int r = key.indexOf('}');
                    int c = key.indexOf(":claimed:");
                    if (l < 0 || r < 0 || c < 0) {
                        continue;
                    }
                    Long templateId = Long.valueOf(key.substring(l + 1, r));
                    Long userId = Long.valueOf(key.substring(c + ":claimed:".length()));
                    Long dbCount = userCouponMapper.countIssued(userId, templateId);
                    int redisCount = 0;
                    String v = stringRedisTemplate.opsForValue().get(key);
                    if (v != null) {
                        try { redisCount = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                    }
                    int expected = dbCount == null ? 0 : dbCount.intValue();
                    if (redisCount != expected) {
                        stringRedisTemplate.opsForValue().set(key, String.valueOf(expected));
                        repaired++;
                        log.warn("[券对账] 限领计数修复: templateId={}, userId={}, {}→{}（以已发券为准）",
                                templateId, userId, redisCount, expected);
                    }
                } catch (Exception ex) {
                    log.warn("[券对账] claimed 键解析失败(跳过): key={}", key, ex);
                }
            }
        } catch (Exception e) {
            log.error("[券对账] claimed 扫描失败, 本轮跳过限领计数对账", e);
            return repaired;
        }
        log.info("[券对账] 限领计数对账完成: 扫描 {} 个键, 修复 {}", scanned, repaired);
        return repaired;
    }

    /**
     * 检查"卡住的 Outbox"：status=0（待发送）且超过 5 分钟未发出 → 领取请求可能未送达消费端
     * （用户已被扣限领/库存但拿不到券）。这里只告警 + 打指标，补发由 CouponOutboxSenderJob 负责。
     */
    private void checkStuckOutbox() {
        try {
            Long stuck = outboxMapper.countStuck(java.time.LocalDateTime.now().minusMinutes(5));
            if (stuck != null && stuck > 0) {
                log.error("[券对账] 存在卡住的 Outbox 记录 {} 条（status=0 超 5 分钟）: "
                        + "用户可能已被扣限领/库存但未拿到券, 需人工核对", stuck);
                businessMetrics.recordCouponAction("outbox_stuck", false);
            }
        } catch (Exception e) {
            log.warn("[券对账] Outbox 卡单检查失败(不影响对账): {}", e.getMessage());
        }
    }

    private static String stockKey(Long templateId) {
        return String.format(STOCK_KEY_TPL, templateId);
    }

    /**
     * 库存对账修复（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 2 * * ?（每天凌晨 2 点）
     */
    @XxlJob("couponReconcileJob")
    public void reconcile() {
        try {
            int repairCount = doReconcile();
            XxlJobHelper.handleSuccess("对账修复完成，修复 " + repairCount + " 个模板");
        } catch (Exception e) {
            log.error("[券对账] 执行异常", e);
            XxlJobHelper.handleFail("券对账异常: " + e.getMessage());
        }
    }

    /**
     * 实际的对账逻辑
     *
     * @return 修复的模板数
     */
    private int doReconcile() {
        log.info("[券对账] 开始执行...");
        long startTime = System.currentTimeMillis();
        int repairCount = 0;

        // 对账覆盖所有未删除模板；状态/过期仅影响能否领取，不影响历史一致性修复
        List<CouponTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>()
                        .eq(CouponTemplate::getDeleted, 0));

        log.info("[券对账] 待对账模板数: {}", templates.size());

        for (CouponTemplate template : templates) {
            String key = stockKey(template.getId());
            String redisStockStr = stringRedisTemplate.opsForValue().get(key);

            // Redis 未初始化 → 从 MySQL 补全（stock Key 应持久，不带 TTL）
            if (redisStockStr == null) {
                stringRedisTemplate.opsForValue().set(key,
                        String.valueOf(template.getRemainCount()));
                repairCount++;
                log.info("[券对账] 补全Redis库存: templateId={}, stock={}",
                        template.getId(), template.getRemainCount());
                continue;
            }

            int redisStock;
            try {
                redisStock = Integer.parseInt(redisStockStr);
            } catch (NumberFormatException e) {
                log.error("[券对账] Redis库存值非法: templateId={}, value={}", template.getId(), redisStockStr, e);
                continue;
            }
            int mysqlRemain = template.getRemainCount();

            // 不一致 → 以 Redis 为准修复 MySQL（Redis 是实时扣减的权威数据源）
            if (redisStock != mysqlRemain) {
                // 只更新 remain_count（原生 SQL：不依赖 MP lambda 缓存，且不覆盖并发的扣减）
                templateMapper.updateRemainCountOnly(template.getId(), redisStock);
                repairCount++;
                log.info("[券对账] 修复: templateId={}, name={}, redis: {} → mysql: {}",
                        template.getId(), template.getName(), redisStock, mysqlRemain);
            }
        }

        // 【新增】限领计数对账（claimed 计数无 TTL，清库/丢 key 后限领会失效 → 以已发券为准修正）
        // + 卡住 Outbox 检查（用户被扣限领/库存却拿不到券的异常必须可见）
        repairCount += reconcileClaimedCounters();
        checkStuckOutbox();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[券对账] 完成: 对账{}个模板, 修复{}个, 耗时{}ms",
                templates.size(), repairCount, elapsed);

        return repairCount;
    }
}
