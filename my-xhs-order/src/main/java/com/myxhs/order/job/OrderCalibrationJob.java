package com.myxhs.order.job;

import com.myxhs.order.entity.Order;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.service.OrderEventService;
import com.myxhs.common.metrics.BusinessMetrics;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单事件流校准（每日干跑，只报告不覆盖）
 * <p>
 * 背景：状态更新与事件落库理论上同事务，但人工改库/补偿重放/历史数据仍可能造成分叉。
 * 已有手动端点 {@code POST /api/order/internal/reconcile/{orderId}}，本任务把它"自动化"：
 * 每日扫描"已支付/已发货且创建于 2~8 天前"的订单做 dry-run，发现差异只告警 + 打指标，
 * 修复仍走人工端点（方向保护：终态差异绝不自动覆盖）。
 * </p>
 * <p>
 * 扫描边界：走 (status, created_at) 索引 + 时间窗 + 每分片 LIMIT 200（无分片键 → 广播各分片）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCalibrationJob {

    private static final int LIMIT = 200;

    private final OrderMapper orderMapper;
    private final OrderEventService orderEventService;
    private final BusinessMetrics businessMetrics;

    @XxlJob("orderEventCalibrationJob")
    public void calibrate() {
        try {
            int checked = 0;
            int mismatch = 0;
            // 窗口：创建于 8 天前 ~ 2 天前（避开"仍在流转中"的订单；每日滚动覆盖）
            List<Order> orders = orderMapper.selectCalibrationCandidates(
                    LocalDateTime.now().minusDays(8),
                    LocalDateTime.now().minusDays(2),
                    LIMIT);
            for (Order order : orders) {
                checked++;
                try {
                    Map<String, Object> report =
                            orderEventService.reconcileStatus(order.getId(), order.getUserId(), false);
                    Object action = report.get("action");
                    if (action != null && !"CONSISTENT".equals(action)) {
                        mismatch++;
                        businessMetrics.recordOrderCalibration("mismatch");
                        log.error("[订单校准] 状态与事件流分叉(需人工核对): orderId={}, report={}",
                                order.getId(), report);
                    }
                } catch (Exception e) {
                    log.warn("[订单校准] 单笔校准失败(跳过): orderId={}", order.getId(), e);
                }
            }
            if (mismatch == 0) {
                businessMetrics.recordOrderCalibration("consistent");
            }
            XxlJobHelper.handleSuccess("订单校准干跑完成: 检查 " + checked + " 单, 差异 " + mismatch + " 单");
            log.info("[订单校准] 完成: checked={}, mismatch={}", checked, mismatch);
        } catch (Exception e) {
            log.error("[订单校准] 执行异常", e);
            XxlJobHelper.handleFail("订单校准异常: " + e.getMessage());
        }
    }
}
