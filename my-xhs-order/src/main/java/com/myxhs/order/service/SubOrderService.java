package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.SubOrder;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.mapper.SubOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 子订单服务（ITER-001 拆单）
 * <p>
 * 职责：
 * 1. <b>父单状态聚合</b>：{@link #computeParentStatus} 为纯函数（穷尽规则、单向）；
 *    子单流转后调用 {@link #recomputeParentStatus} 收敛父单状态（读全子单 → 算 → CAS 事件流转 + 重试）；
 * 2. <b>子单批量推进</b>：支付成功（0→1）/ 未支付终结（0→4，与父单同本地事务）/ 全退收敛（→5）。
 * </p>
 * <p>
 * 聚合规则（穷尽，AC-4）：
 * <pre>
 * 全取消 → 4；全退款 → 5；全终态（混合：部分取消/退款 + 部分完成）→ 3；
 * 存在活跃子单时按活跃子单进度取值（已终态子单不拉低父单）：
 *   活跃全待付款 → 0；活跃全 ≥已发货 → 2；其余 → 1
 * </pre>
 * 收敛性：任何子单流转都会触发重算；CAS 冲突重试，最终一致（兜底：订单校准任务）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubOrderService {

    private final SubOrderMapper subOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderEventService orderEventService;

    // ==================== 聚合（纯函数 + 收敛） ====================

    /** 终态：已完成(3)/已取消(4)/已退款(5) */
    private static boolean isTerminal(int status) {
        return status >= SubOrder.STATUS_COMPLETED;
    }

    /**
     * 父单状态聚合函数（纯函数，穷尽规则；可表驱动测试）
     *
     * @throws IllegalArgumentException 子单列表为空（父单聚合不变量被破坏）
     */
    public static int computeParentStatus(List<SubOrder> subs) {
        if (subs == null || subs.isEmpty()) {
            throw new IllegalArgumentException("子单列表为空：父单聚合不变量被破坏");
        }
        // 全终态分支（终态判定优先）
        boolean allCancelled = subs.stream().allMatch(s -> s.getStatus() == SubOrder.STATUS_CANCELLED);
        boolean allRefunded = subs.stream().allMatch(s -> s.getStatus() == SubOrder.STATUS_REFUNDED);
        if (allCancelled) {
            return 4;
        }
        if (allRefunded) {
            return 5;
        }
        if (subs.stream().allMatch(s -> isTerminal(s.getStatus()))) {
            // 混合终态（部分取消/退款 + 部分完成）→ 交易完成；"部分"的语义由子单表达
            return 3;
        }
        // 存在活跃子单：按活跃子单进度取值（已终态子单不拉低父单）
        List<SubOrder> active = subs.stream().filter(s -> !isTerminal(s.getStatus())).toList();
        if (active.stream().allMatch(s -> s.getStatus() == SubOrder.STATUS_PENDING_PAYMENT)) {
            return 0;
        }
        if (active.stream().allMatch(s -> s.getStatus() >= SubOrder.STATUS_SHIPPED)) {
            return 2;
        }
        return 1;
    }

    /**
     * 父单状态重算（子单流转后调用；幂等、可重试、最终收敛）
     *
     * @return true = 本次调用发生了父单状态流转（调用方据此决定快照等收尾动作）
     */
    public boolean recomputeParentStatus(Long userId, Long orderId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<SubOrder> subs = subOrderMapper.selectByOrderId(orderId, userId);
            if (subs.isEmpty()) {
                log.error("[子单聚合] 子单缺失（不变量破坏）: orderId={}", orderId);
                return false;
            }
            int target = computeParentStatus(subs);
            Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getId, orderId));
            if (order == null) {
                log.error("[子单聚合] 父单不存在: orderId={}", orderId);
                return false;
            }
            if (order.getStatus() != null && order.getStatus() == target) {
                return false; // 已收敛
            }
            String eventType = aggregateEvent(target);
            if (eventType == null) {
                // 目标 0/1/4 由专属流程负责（支付推进/未支付终结）；出现即异常，告警人工核对
                log.warn("[子单聚合] 目标状态 {} 非聚合事件路径（应由专属流程收敛）: orderId={}, current={}",
                        target, orderId, order.getStatus());
                return false;
            }
            Integer from = order.getStatus();
            try {
                orderEventService.appendEvent(order, eventType,
                        Map.of("source", "SUB_ORDER_AGGREGATE", "aggregatedStatus", target));
                log.info("[子单聚合] 父单状态收敛: orderId={}, {}->{}", orderId, from, target);
                return true;
            } catch (IllegalStateException | com.myxhs.common.exception.BizException e) {
                // CAS/序号冲突：重读重算重试（下一次子单流转/校准任务兜底收敛）
                log.warn("[子单聚合] 收敛冲突, 重试: orderId={}, attempt={}, cause={}",
                        orderId, attempt + 1, e.getMessage());
            }
        }
        log.error("[子单聚合] 重试后仍未收敛（下一次子单流转/校准任务兜底）: orderId={}", orderId);
        return false;
    }

    /** 聚合目标 → 事件类型（2/3/5 可聚合表达；0/1/4 走专属流程） */
    private static String aggregateEvent(int target) {
        return switch (target) {
            case 2 -> OrderEventService.EVENT_ALL_SUBS_SHIPPED;
            case 3 -> OrderEventService.EVENT_ALL_SUBS_COMPLETED;
            case 5 -> OrderEventService.EVENT_REFUNDED;
            default -> null;
        };
    }

    // ==================== 子单批量推进 ====================

    /** 支付成功：订单全部子单 0→1（幂等；与父单 EVENT_PAID 同流程） */
    public int advanceAllToPaid(Long userId, Long orderId) {
        int advanced = subOrderMapper.advanceAllToPaid(orderId, userId);
        log.info("[子单] 支付推进(0→1): orderId={}, advanced={}", orderId, advanced);
        return advanced;
    }

    /** 未支付终结：订单全部子单 0→4（与父单终结同一本地事务内调用） */
    public int cancelAll(Long userId, Long orderId) {
        int cancelled = subOrderMapper.cancelAll(orderId, userId);
        log.info("[子单] 终结取消(0→4): orderId={}, cancelled={}", orderId, cancelled);
        return cancelled;
    }

    /** 全退收敛：订单全部子单（1/2/3）→5（幂等） */
    public int refundAll(Long userId, Long orderId) {
        int refunded = subOrderMapper.refundAll(orderId, userId);
        log.info("[子单] 全退收敛(→5): orderId={}, refunded={}", orderId, refunded);
        return refunded;
    }
}
