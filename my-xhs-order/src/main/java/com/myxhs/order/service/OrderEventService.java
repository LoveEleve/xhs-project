package com.myxhs.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderEvent;
import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.mapper.OrderEventMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.OrderNoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final OrderEventMapper orderEventMapper;
    private final OrderMapper orderMapper;
    private final OrderNoMappingRepository orderNoMappingRepository;
    private final ObjectMapper objectMapper;

    public static final String EVENT_CREATED = "ORDER_CREATED";
    public static final String EVENT_PAID = "ORDER_PAID";
    public static final String EVENT_CANCELLED = "ORDER_CANCELLED";
    public static final String EVENT_DELIVERED = "ORDER_DELIVERED";
    public static final String EVENT_COMPLETED = "ORDER_COMPLETED";
    public static final String EVENT_REFUNDED = "ORDER_REFUNDED";
    public static final String EVENT_TIMEOUT_CANCELLED = "ORDER_TIMEOUT_CANCELLED";
    /** 库存预扣失败自动取消（订单侧收口；与用户取消/超时取消区分，便于审计归因） */
    public static final String EVENT_INVENTORY_FAILED_CANCELLED = "ORDER_INVENTORY_FAILED_CANCELLED";

    /**
     * 事件类型 → 目标状态（Integer）的映射
     * <p>
     * 状态值：0-待付款 1-已付款 2-已发货 3-已完成 4-已取消 5-已退款
     * </p>
     */
    private static final Map<String, Integer> EVENT_STATUS_MAP = Map.of(
        // T-110（2026-08-15）：EVENT_CREATED 目标状态改 -1——原值 0 与创建时订单 status=0 相同，
        // 导致 appendEvent 快速幂等检查 currentStatus.equals(targetStatus) 恒真 → 创建事件从不落库（事件链断）。
        // -1 表示"创建"非状态流转，to_status=-1 仅作事件记录（getLatestStatus 对 ORDER_CREATED 特殊处理见下）
        EVENT_CREATED, -1,
        EVENT_PAID, 1,
        EVENT_DELIVERED, 2,
        EVENT_COMPLETED, 3,
        EVENT_CANCELLED, 4,
        EVENT_TIMEOUT_CANCELLED, 4,
        EVENT_INVENTORY_FAILED_CANCELLED, 4,
        EVENT_REFUNDED, 5
    );

    /**
     * 状态机转移白名单（显式化）：事件 → 允许的前置状态集合
     * <p>
     * 原实现转移合法性只靠"各调用点的守卫 + 条件 UPDATE 的 WHERE 前置值"（隐式状态机），
     * 新增流转点时容易漏守卫。此处集中校验：非法的 from→to 直接拒绝（业务异常），
     * 让"状态机"有单一可审计的定义；集合按现有业务流放宽（含补偿/校准的重放场景），
     * 只拦明确非法的组合（如 0→2 未支付发货、0→3 未支付完成）。
     * </p>
     */
    private static final Map<String, java.util.Set<Integer>> ALLOWED_FROM = Map.of(
        EVENT_PAID, java.util.Set.of(0),
        EVENT_DELIVERED, java.util.Set.of(1),
        EVENT_COMPLETED, java.util.Set.of(2),
        EVENT_CANCELLED, java.util.Set.of(0, 1, 2),
        EVENT_TIMEOUT_CANCELLED, java.util.Set.of(0),
        EVENT_REFUNDED, java.util.Set.of(1, 2, 3, 4),
        EVENT_INVENTORY_FAILED_CANCELLED, java.util.Set.of(0)
        // EVENT_CREATED 目标为 -1（非状态流转），不做转移校验
    );

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void appendEvent(Order order, String eventType, Object payload) {
        Integer currentStatus = order.getStatus();
        Integer targetStatus = EVENT_STATUS_MAP.get(eventType);
        if (targetStatus == null) {
            throw new IllegalArgumentException("未知事件类型: " + eventType);
        }

        // 快速幂等检查：当前状态已经是目标状态，无需重复追加
        if (currentStatus.equals(targetStatus)) {
            log.info("[OrderEvent] 幂等跳过：订单已是目标状态: orderId={}, status={}, eventType={}",
                order.getId(), currentStatus, eventType);
            return;
        }

        // 显式状态机校验：拒绝明确非法的流转（如 0→2 未支付发货、0→3 未支付完成）
        java.util.Set<Integer> allowedFrom = ALLOWED_FROM.get(eventType);
        if (allowedFrom != null && !allowedFrom.contains(currentStatus)) {
            log.warn("[OrderEvent] 非法状态流转被拒绝: orderId={}, {}: {}->{}",
                    order.getId(), eventType, currentStatus, targetStatus);
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.ORDER_STATUS_ERROR,
                    "订单状态不允许此流转（" + currentStatus + "→" + targetStatus + "）");
        }

        // 快路径用普通读（不持锁）：常规单写场景无锁开销，也避免"事件锁→订单锁"与调用方
        // "订单锁→事件锁"形成反向锁序导致死锁。并发冲突处**不回抢锁**：序号冲突直接整体回滚，
        // 由调用方重试（重试时幂等检查看到状态已收敛 → 跳过）。
        OrderEvent lastEvent = orderEventMapper.findLastByOrderIdAndUserId(order.getId(), order.getUserId());
        int nextSeq = (lastEvent == null) ? 1 : lastEvent.getEventSeq() + 1;

        // 幂等检查：检查是否已存在相同的事件记录（同一 event_seq 防重）
        if (lastEvent != null && lastEvent.getEventType().equals(eventType)) {
            // T-123（2026-08-16）：重复事件但状态不一致时仍收敛状态——
            // 修复前直接 return，事件存在即跳过状态流转：人工重置状态（测试/运维操纵）
            // 或补偿重放时，事件在但状态回退 → 永久不一致（G5-02-16 实证：ORDER_REFUNDED
            // 事件存在 + 订单被重置为 1 → xxl#8 补偿通知 200 但状态永不回 5）
            // 修复：不重复插入事件，但用乐观锁把状态收敛到目标值
            if (!currentStatus.equals(targetStatus) && targetStatus >= 0) {
                log.warn("[OrderEvent] 重复事件但状态不一致，收敛状态: orderId={}, eventType={}, status {}->{}",
                    order.getId(), eventType, currentStatus, targetStatus);
                int affected = orderMapper.updateStatusWithLock(
                    order.getId(), order.getUserId(), currentStatus, targetStatus);
                if (affected == 0) {
                    // 收敛乐观锁失败 = 订单状态已被并发流转（用户取消 vs 支付 vs 发货）→ 属业务冲突，
                    // 抛业务异常让调用方/用户得到 4xx 语义（原实现抛 IllegalStateException → 全局 500）
                    log.warn("[OrderEvent] 状态收敛失败(并发流转, 业务冲突): orderId={}, expected={}",
                            order.getId(), currentStatus);
                    throw new com.myxhs.common.exception.BizException(
                            com.myxhs.common.response.ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，请刷新后重试");
                }
                order.setStatus(targetStatus);
            } else {
                log.info("[OrderEvent] 幂等跳过：重复事件: orderId={}, eventType={}, seq={}",
                    order.getId(), eventType, nextSeq - 1);
            }
            return;
        }

        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.warn("[OrderEvent] 载荷序列化失败: orderId={}", order.getId(), e);
            }
        }

        OrderEvent event = OrderEvent.builder()
            .orderId(order.getId())
            .userId(order.getUserId())
            .eventType(eventType)
            .fromStatus(currentStatus)
            .toStatus(targetStatus)
            .payload(payloadJson)
            .eventSeq(nextSeq)
            .eventTime(LocalDateTime.now())
            .build();
        // 幂等插入事件（INSERT IGNORE + 唯一索引 uk_order_event_seq 防重）
        int inserted = orderEventMapper.insertIgnore(event);
        if (inserted == 0) {
            // 序号冲突 ⇒ 另一事务同时在为**同一订单**追加事件（seq 是每订单维度）。
            // 该场景必然伴随状态乐观锁竞争：本事务的 status 更新也会失败，所以正确语义是"整体回滚让调用方重试"，
            // 而不是在此处加锁重算 seq——加锁会引入"事件锁↔订单锁"反向锁序的死锁面（已排除该设计）。
            // 重试时 appendEvent 的幂等检查会看到状态已是目标值 → 直接跳过，不会无限失败。
            log.warn("[OrderEvent] 事件序号冲突(并发流转), 回滚本次流转由调用方重试: orderId={}, eventType={}, seq={}",
                    order.getId(), eventType, nextSeq);
            throw new IllegalStateException(
                    String.format("订单事件序号冲突: orderId=%d, seq=%d", order.getId(), nextSeq));
        }
        if (inserted > 0) {
            log.info("[OrderEvent] 事件追加: orderId={}, {}:{}->{}, seq={}",
                order.getId(), eventType, currentStatus, targetStatus, nextSeq);
        }

        // T-110（2026-08-15）：EVENT_CREATED 的 targetStatus=-1（"创建"非状态流转）——
        // 跳过订单状态更新（status 已由创建事务置 0），仅写入事件记录。
        // 若不跳过，updateStatusWithLock 会把 status 更新为 -1（非法状态，破坏状态机 0-5）
        if (targetStatus >= 0) {
            // 使用乐观锁更新状态：只有当前状态匹配时才更新，防止并发覆盖
            int affected = orderMapper.updateStatusWithLock(
                order.getId(), order.getUserId(), currentStatus, targetStatus);
            if (affected == 0) {
                log.error("[OrderEvent] 乐观锁更新失败：订单状态已被其他线程修改: orderId={}, expected={}, actual={}",
                    order.getId(), currentStatus, order.getStatus());
                throw new IllegalStateException(
                    String.format("订单状态并发冲突: orderId=%d, expected=%d", order.getId(), currentStatus));
            }
            order.setStatus(targetStatus);
        }
    }

    public List<OrderEvent> getEventStream(Long orderId) {
        return orderEventMapper.findByOrderId(orderId);
    }

    /**
     * 按订单 + 用户查询事件流（带分片键，单分片路由）
     */
    public List<OrderEvent> getEventStream(Long orderId, Long userId) {
        return orderEventMapper.findByOrderIdAndUserId(orderId, userId);
    }

    public Integer replayStatus(Long orderId) {
        return replayStatus(orderEventMapper.findByOrderId(orderId));
    }

    /**
     * 事件流重放：取最后一个真实状态流转事件的 to_status
     * <p>
     * T-110：EVENT_CREATED 的 to_status=-1 表示"创建"（非状态流转）——重放时跳过，
     * 取最后一个真实流转事件的状态；全为创建事件时返回 0（待付款）。
     * </p>
     */
    private Integer replayStatus(List<OrderEvent> events) {
        if (events == null || events.isEmpty()) return null;
        for (int i = events.size() - 1; i >= 0; i--) {
            Integer toStatus = events.get(i).getToStatus();
            if (toStatus != null && toStatus >= 0) {
                return toStatus;
            }
        }
        return 0;
    }

    /**
     * 订单状态重建与校准（事件溯源回放）
     * <p>
     * t_order_event 是订单状态变迁的唯一事实来源（每次流转先落事件、再条件更新状态），
     * 因此可用事件流重放结果校准订单当前状态：
     * - 一致 → CONSISTENT
     * - 不一致且 repair=false → 仅报告（dry-run，不写库）
     * - 不一致且 repair=true → 以条件更新（WHERE status=当前值）修复，并补齐缺失的里程碑时间
     * </p>
     * 适用场景：状态更新与事件落库之间出现异常（进程崩溃、人工改库、补偿脚本误操作）后的数据修复。
     *
     * @param orderId 订单ID
     * @param userId  下单用户ID（分片键，保证路由到正确分片）
     * @param repair  是否执行修复
     * @return 校准报告（found / currentStatus / replayedStatus / eventCount / action）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Map<String, Object> reconcileStatus(Long orderId, Long userId, boolean repair) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("orderId", orderId);

        // userId 缺省时经订单号映射表反查（与支付回调同一套路由依据）
        Long shardUserId = userId;
        if (shardUserId == null) {
            OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
            shardUserId = mapping != null ? mapping.getUserId() : null;
        }
        if (shardUserId == null) {
            report.put("found", false);
            report.put("action", "ORDER_NOT_FOUND");
            return report;
        }
        report.put("userId", shardUserId);

        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, shardUserId)
                .eq(Order::getId, orderId));
        if (order == null) {
            report.put("found", false);
            report.put("action", "ORDER_NOT_FOUND");
            return report;
        }

        List<OrderEvent> events = orderEventMapper.findByOrderIdAndUserId(orderId, shardUserId);
        Integer currentStatus = order.getStatus();
        Integer replayed = replayStatus(events);
        report.put("found", true);
        report.put("currentStatus", currentStatus);
        report.put("replayedStatus", replayed);
        report.put("eventCount", events.size());

        if (replayed == null) {
            report.put("action", "NO_EVENT_STREAM");
            return report;
        }
        if (Objects.equals(currentStatus, replayed)) {
            report.put("action", "CONSISTENT");
            return report;
        }
        // 方向保护：只有"合法状态迁移"才允许自动修复。
        // 白名单之外（含事件流缺失导致的回退、终态被改写）一律只报告——
        // 避免在历史订单缺少事件、事件丢失时把订单状态写回旧值（例如已支付被改回待付款）。
        if (!isLegalTransition(currentStatus, replayed)) {
            report.put("action", "MISMATCH_NOT_LEGAL_TRANSITION_REQUIRES_REVIEW");
            log.warn("[订单校准] 差异不构成合法状态迁移, 仅报告: orderId={}, current={}, replayed={}",
                    orderId, currentStatus, replayed);
            return report;
        }
        if (!repair) {
            report.put("action", "MISMATCH_REPORT_ONLY");
            log.warn("[订单校准] 状态偏差(仅报告): orderId={}, current={}, replayed={}", orderId, currentStatus, replayed);
            return report;
        }

        int affected = orderMapper.updateStatusWithLock(orderId, shardUserId, currentStatus, replayed);
        if (affected == 0) {
            report.put("action", "REPAIR_FAILED_CONCURRENT_UPDATE");
            log.warn("[订单校准] 修复冲突(状态已被并发修改): orderId={}, current={}, replayed={}",
                    orderId, currentStatus, replayed);
            return report;
        }
        fillMissingTimestamp(order, orderId, shardUserId, replayed);
        report.put("action", "REPAIRED");
        if (replayed == 4 || replayed == 5) {
            report.put("sideEffectHint", "仅校准状态；库存/优惠券等补偿动作由补偿与对账任务收敛");
        }
        log.warn("[订单校准] 已修复状态偏差: orderId={}, {} → {}", orderId, currentStatus, replayed);
        return report;
    }

    /**
     * 合法状态迁移白名单（与订单状态机一致）：
     * 0→1 支付、0→4 取消/超时关单、1→2 发货、1→5 退款、2→3 确认收货、3→5 完成后退款。
     * 白名单之外一律不自动修复。
     */
    private static final Set<String> LEGAL_TRANSITIONS = Set.of(
            "0->1", "0->4", "1->2", "1->5", "2->3", "3->5");

    private boolean isLegalTransition(Integer currentStatus, Integer replayed) {
        if (currentStatus == null || replayed == null) {
            return false;
        }
        return LEGAL_TRANSITIONS.contains(currentStatus + "->" + replayed);
    }

    /**
     * 修复状态后补齐缺失的里程碑时间（该列已有值不覆盖；各 UPDATE 自带 status 条件，状态不符时自然 no-op）
     */
    private void fillMissingTimestamp(Order order, Long orderId, Long userId, Integer replayed) {
        if (replayed == null) {
            return;
        }
        switch (replayed) {
            case 1 -> {
                if (order.getPaidAt() == null) orderMapper.setPaidAt(orderId, userId);
            }
            case 2 -> {
                if (order.getDeliveredAt() == null) orderMapper.setDeliveredAt(orderId, userId);
            }
            case 3 -> {
                if (order.getCompletedAt() == null) orderMapper.setCompletedAt(orderId, userId);
            }
            case 4 -> {
                if (order.getCancelledAt() == null) orderMapper.setCancelledAt(orderId, userId);
            }
            default -> {
            }
        }
    }
}
