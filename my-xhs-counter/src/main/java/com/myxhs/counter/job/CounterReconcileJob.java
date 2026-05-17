package com.myxhs.counter.job;

import com.myxhs.counter.service.CounterService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 计数对账修复定时任务（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 3 点执行，扫描 DB 所有计数记录，与 Redis 对比，差异自动修复。
 * <p>
 * 修复策略：
 * - Redis 有值，DB 有值，不一致 → 以 Redis 为准（Redis 是实时更新的权威源）
 * - Redis 值为 0，DB 有值 → 以 DB 为准（Redis 可能数据丢失）
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterReconcileJob {

    private final CounterService counterService;

    /**
     * 计数对账修复（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 3 * * ?（每天凌晨 3 点）
     */
    @XxlJob("counterReconcileJob")
    public void reconcile() {
        log.info("[对账定时任务] 开始执行...");
        try {
            int fixedCount = counterService.reconcile();
            log.info("[对账定时任务] 执行完成，修复 {} 条", fixedCount);
            XxlJobHelper.handleSuccess("对账修复完成，修复 " + fixedCount + " 条");
        } catch (Exception e) {
            log.error("[对账定时任务] 执行异常", e);
            XxlJobHelper.handleFail("对账修复异常: " + e.getMessage());
        }
    }
}
