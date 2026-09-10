package com.myxhs.payment.job;

import com.myxhs.payment.service.PaymentService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付对账定时任务（XXL-Job 分布式调度）
 * <p>
 * 【修复】PaymentService.reconcile()（扫描支付成功单，核对订单状态，不一致则补偿通知）
 * 原实现无任何调度入口（无 @XxlJob/@Scheduled/接口调用）→ 对账逻辑从不执行（死代码）。
 * 本 Job 为其补齐 XXL-Job 入口，使"每天凌晨对账"注释语义成立。
 * </p>
 * <p>
 * 对账逻辑：扫描 t_payment status=1 的支付单 → Feign 查订单支付金额 →
 * 若订单仍待付款（返回非空金额）则 notifyPaySuccess 补偿，保证支付/订单最终一致。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private final PaymentService paymentService;

    /**
     * 支付对账（XXL-Job Handler）
     * <p>
     * Admin 建议 Cron = 0 0 3 * * ?（每天凌晨 3 点）
     */
    @XxlJob("paymentReconcileJob")
    public void reconcile() {
        log.info("[定时任务] 支付对账开始");
        try {
            paymentService.reconcile();
            XxlJobHelper.handleSuccess("支付对账完成");
        } catch (Exception e) {
            log.error("[定时任务] 支付对账异常", e);
            XxlJobHelper.handleFail("支付对账异常: " + e.getMessage());
        }
    }
}
