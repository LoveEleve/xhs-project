package com.myxhs.order.job;

import com.myxhs.order.service.AftersaleService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 售后单恢复任务（XXL-Job）
 * <p>
 * 处理两类异常单：
 * 1. 退款中卡死（超过 5 分钟未落定）→ 查支付域事实：已成功则置完成并补库存，无记录则转失败；
 * 2. 退款失败自动重试（退避 10 分钟、最多 3 次）。
 * </p>
 * 幂等：所有流转仍是条件更新，与人工审核/重试并发时只有一个赢家。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AftersaleRecoveryJob {

    private final AftersaleService aftersaleService;

    @XxlJob("aftersaleRecoveryJob")
    public void recover() {
        try {
            var summary = aftersaleService.recoverStuckAndRetry();
            XxlJobHelper.handleSuccess("售后恢复完成: " + summary);
        } catch (Exception e) {
            log.error("[售后恢复] 执行异常", e);
            XxlJobHelper.handleFail("售后恢复异常: " + e.getMessage());
        }
    }
}
