package com.myxhs.payment.job;

import com.myxhs.payment.service.SettlementService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 结算任务（XXL-Job）
 * <p>
 * 1. settlementBillJob：每日 02:00 生成 T-1 全渠道账单（幂等：已有账单走重跑覆盖）；
 * 2. settlementReconcileJob：每日 03:00 拉取/导入渠道对账文件后对账（以 T-1 为账单日）。
 * </p>
 * 两个任务都按渠道串行、单渠道失败不影响其他渠道（服务内逐个 try/catch）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementJob {

    private final SettlementService settlementService;

    @XxlJob("settlementBillJob")
    public void generateBill() {
        LocalDate billDate = LocalDate.now().minusDays(1);
        try {
            var bills = settlementService.generateAll(billDate, false);
            XxlJobHelper.handleSuccess("T-1(" + billDate + ") 账单生成: " + bills.size() + " 个渠道");
        } catch (Exception e) {
            log.error("[结算] 账单生成任务异常", e);
            XxlJobHelper.handleFail("账单生成异常: " + e.getMessage());
        }
    }

    @XxlJob("settlementReconcileJob")
    public void reconcile() {
        LocalDate billDate = LocalDate.now().minusDays(1);
        try {
            var bills = settlementService.reconcileAll(billDate);
            XxlJobHelper.handleSuccess("T-1(" + billDate + ") 对账: " + bills.size() + " 个渠道");
        } catch (Exception e) {
            log.error("[结算] 对账任务异常", e);
            XxlJobHelper.handleFail("对账异常: " + e.getMessage());
        }
    }
}
