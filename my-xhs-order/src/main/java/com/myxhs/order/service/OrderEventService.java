package com.myxhs.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderEvent;
import com.myxhs.order.mapper.OrderEventMapper;
import com.myxhs.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final OrderEventMapper orderEventMapper;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public static final String EVENT_CREATED = "ORDER_CREATED";
    public static final String EVENT_PAID = "ORDER_PAID";
    public static final String EVENT_CANCELLED = "ORDER_CANCELLED";
    public static final String EVENT_DELIVERED = "ORDER_DELIVERED";
    public static final String EVENT_COMPLETED = "ORDER_COMPLETED";
    public static final String EVENT_REFUNDED = "ORDER_REFUNDED";
    public static final String EVENT_TIMEOUT_CANCELLED = "ORDER_TIMEOUT_CANCELLED";

    /**
     * 事件类型 → 目标状态（Integer）的映射
     * <p>
     * 状态值：0-待付款 1-已付款 2-已发货 3-已完成 4-已取消 5-已退款
     * </p>
     */
    private static final Map<String, Integer> EVENT_STATUS_MAP = Map.of(
        EVENT_CREATED, 0,
        EVENT_PAID, 1,
        EVENT_DELIVERED, 2,
        EVENT_COMPLETED, 3,
        EVENT_CANCELLED, 4,
        EVENT_TIMEOUT_CANCELLED, 4,
        EVENT_REFUNDED, 5
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

        OrderEvent lastEvent = orderEventMapper.findLastByOrderId(order.getId());
        int nextSeq = (lastEvent == null) ? 1 : lastEvent.getEventSeq() + 1;

        // 幂等检查：检查是否已存在相同的事件记录（同一 event_seq 防重）
        if (lastEvent != null && lastEvent.getEventType().equals(eventType)) {
            log.info("[OrderEvent] 幂等跳过：重复事件: orderId={}, eventType={}, seq={}",
                order.getId(), eventType, nextSeq - 1);
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
            log.info("[OrderEvent] 幂等跳过：重复事件记录: orderId={}, eventType={}, seq={}",
                order.getId(), eventType, nextSeq);
        } else {
            log.info("[OrderEvent] 事件追加: orderId={}, {}:{}->{}, seq={}",
                order.getId(), eventType, currentStatus, targetStatus, nextSeq);
        }

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

    public List<OrderEvent> getEventStream(Long orderId) {
        return orderEventMapper.findByOrderId(orderId);
    }

    public Integer replayStatus(Long orderId) {
        List<OrderEvent> events = orderEventMapper.findByOrderId(orderId);
        if (events.isEmpty()) return null;
        return events.get(events.size() - 1).getToStatus();
    }
}
