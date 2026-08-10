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

    private static final String STOCK_KEY_TPL = "myxhs:coupon:{%d}:stock";
    private static final Duration TEMPLATE_CACHE_TTL = Duration.ofSeconds(1800);

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

        // 只对启用的、未过期的模板进行对账
        List<CouponTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>()
                        .eq(CouponTemplate::getStatus, 1)
                        .gt(CouponTemplate::getValidEnd, LocalDateTime.now())
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
                templateMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CouponTemplate>()
                                .eq(CouponTemplate::getId, template.getId())
                                .set(CouponTemplate::getRemainCount, redisStock));
                repairCount++;
                log.info("[券对账] 修复: templateId={}, name={}, redis: {} → mysql: {}",
                        template.getId(), template.getName(), redisStock, mysqlRemain);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[券对账] 完成: 对账{}个模板, 修复{}个, 耗时{}ms",
                templates.size(), repairCount, elapsed);

        return repairCount;
    }
}
