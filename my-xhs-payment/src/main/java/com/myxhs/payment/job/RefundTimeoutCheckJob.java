package com.myxhs.payment.job;

import com.myxhs.payment.service.PaymentService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 退款超时检查定时任务（XXL-Job 分布式调度）
 * <p>
 * 每 60 秒扫描一次，检查退款中超时（15 天）的退款单。
 * 超时的退款单标记为"退款关闭"。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * 原先 PaymentService.checkRefundTimeout() 内部的分布式锁可保留作为防御性编程。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundTimeoutCheckJob {

    private final PaymentService paymentService;

    /**
     * 退款超时检查（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0/60 * * * * ?（每 60 秒）
     */
    @XxlJob("refundTimeoutCheckJob")
    public void checkRefundTimeout() {
        log.debug("[定时任务] 退款超时检查开始");
        try {
            paymentService.checkRefundTimeout();
            XxlJobHelper.handleSuccess("退款超时检查完成");
        } catch (Exception e) {
            log.error("[定时任务] 退款超时检查异常", e);
            XxlJobHelper.handleFail("退款超时检查异常: " + e.getMessage());
        }
    }
}
