package com.myxhs.payment.job;

import com.myxhs.payment.service.PaymentService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付超时检查定时任务（XXL-Job 分布式调度）
 * <p>
 * 每 30 秒扫描一次，检查支付单是否超时（30 分钟）。
 * 超时的支付单标记为"支付失败"。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * 原先 PaymentService.checkPaymentTimeout() 内部的分布式锁可保留作为防御性编程。
 * </p>
 * <p>
 * 为什么还需要定时任务？
 * - 支付宝/微信的异步回调可能因网络问题丢失
 * - 定时任务是兜底机制，保证支付单不会永远停留在"待支付"状态
 * - 与 RocketMQ 延时关单消息形成双保险
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutCheckJob {

    private final PaymentService paymentService;

    /**
     * 支付超时检查（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0/30 * * * * ?（每 30 秒）
     */
    @XxlJob("paymentTimeoutCheckJob")
    public void checkPaymentTimeout() {
        log.debug("[定时任务] 支付超时检查开始");
        try {
            paymentService.checkPaymentTimeout();
            XxlJobHelper.handleSuccess("支付超时检查完成");
        } catch (Exception e) {
            log.error("[定时任务] 支付超时检查异常", e);
            XxlJobHelper.handleFail("支付超时检查异常: " + e.getMessage());
        }
    }
}
