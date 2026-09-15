package com.myxhs.ai.approval;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 审批执行崩溃恢复（持久化执行的兜底）：
 * approved 但未落执行结果（进程在批准与执行之间崩溃）→ 扫描并按 id 重试。
 * retryExecution 自带 CAS 抢占，重复触发安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalExecutionRecoveryJob {

    private final JdbcTemplate jdbcTemplate;
    private final ApprovalService approvalService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${myxhs.approval.recovery-delay-seconds:120}")
    private int recoveryDelaySeconds;

    @Value("${myxhs.approval.recovery-batch:20}")
    private int batch;

    @Value("${myxhs.approval.executing-timeout-seconds:600}")
    private int executingTimeoutSeconds;

    @Scheduled(fixedDelayString = "${myxhs.approval.recovery-interval-ms:60000}", initialDelay = 90000)
    public void recover() {
        reclaimStuckExecuting();
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_approval WHERE status='approved' "
                            + "AND (result IS NULL OR result NOT LIKE '%executionStatus%') "
                            + "AND decided_at < NOW() - INTERVAL ? SECOND ORDER BY id LIMIT ?",
                    recoveryDelaySeconds, batch);
        } catch (Exception e) {
            log.warn("[审批恢复] 扫描失败: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            try {
                approvalService.retryExecution(0L, id);
                log.warn("[审批恢复] 已补执行审批 id={}（批准后未落结果）", id);
                if (meterRegistry != null) {
                    meterRegistry.counter("ai_approval_recovered_total").increment();
                }
            } catch (Exception e) {
                log.warn("[审批恢复] 审批 id={} 补执行失败: {}", id, e.getMessage());
            }
        }
    }

    /** 回收"卡在 executing"的记录（执行中进程崩溃）：标记 failed，交人工重试，避免自动重放副作用 */
    void reclaimStuckExecuting() {
        try {
            int reclaimed = jdbcTemplate.update(
                    "UPDATE ai_approval SET result='{\"executionStatus\":\"failed\",\"error\":\"执行中断未落结果，已回收待人工重试\"}' "
                            + "WHERE result LIKE '%executing%' AND decided_at < NOW() - INTERVAL ? SECOND",
                    executingTimeoutSeconds);
            if (reclaimed > 0) {
                log.warn("[审批恢复] 回收卡死 executing 记录 {} 条（>{}s）", reclaimed, executingTimeoutSeconds);
                if (meterRegistry != null) {
                    meterRegistry.counter("ai_approval_reclaimed_total").increment(reclaimed);
                }
            }
        } catch (Exception e) {
            log.warn("[审批恢复] 回收执行超时记录失败: {}", e.getMessage());
        }
    }
}
