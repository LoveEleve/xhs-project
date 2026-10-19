package com.myxhs.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.common.trace.TraceContextHolder;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单状态变更通知发布器（type=5 订单通知，消费端 NotificationEventConsumer）
 * <p>
 * 背景：通知管道（NOTIFICATION_TOPIC + 模板 + SSE + 未读计数）早已具备，但订单域此前未接入，
 * 用户收不到"支付成功/已发货/退款到账"站内通知。本类补齐订单域生产者。
 * </p>
 * <p>
 * 可靠性口径（C-012 F6 升级：fire-and-forget → outbox）：
 * <ul>
 *   <li>事件先落 t_local_message（与订单同库的本地消息表）→ 提交后即时发送 → 成功后 markSuccess；
 *       发送失败/进程崩溃 → LocalMessageRetryJob（30s）补发 → **at-least-once**；</li>
 *   <li>与 04B 终结通知同级（终结走事务消息 + Outbox，状态变更走本类 Outbox）；
 *       社交通知（点赞/评论/关注）保持 best-effort（C-010 口径）——交易类通知不适用"可丢"口径；</li>
 *   <li>重复投递窗口：markSuccess 失败后补发会造成同一状态重复入聚合（ORDER 型标题不社交化、
 *       count +1 影响轻微）——窄窗口，注释明示；</li>
 *   <li>落库失败等异常不阻断交易主链路（catch + 日志；通知是加速，订单列表可见）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationPublisher {

    /** 通知 topic（LocalMessageRetryJob 补发映射复用） */
    public static final String NOTIFICATION_TOPIC = "NOTIFICATION_TOPIC";

    /** 本地消息操作类型：订单状态变更通知（补发映射见 LocalMessageRetryJob.resolveTopic） */
    public static final String OPERATION_TYPE_ORDER_STATUS_NOTIFICATION = "ORDER_STATUS_NOTIFICATION";

    /** 2=评论 3=关注 4=系统 5=订单（与 NotificationType 对齐） */
    private static final int TYPE_ORDER = 5;
    /** 目标类型：1-笔记 2-商品 3-订单（与 NotificationEventDTO 对齐） */
    private static final int TARGET_TYPE_ORDER = 3;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final LocalMessageMapper localMessageMapper; // C-012 F6：outbox 落库

    /**
     * 发布订单状态变更通知（C-012 F6：落库 → 提交后发送 → 失败补发）
     *
     * @param userId    收件人（订单归属人）
     * @param orderId   订单 ID
     * @param orderNo   订单号（模板/展示用）
     * @param statusText 状态文案，如"支付成功，我们将尽快为你发货"
     * @param extraInfo 附加信息（物流公司/单号等，可为空）
     */
    public void publishStatusChanged(Long userId, Long orderId, String orderNo,
                                     String statusText, String extraInfo) {
        if (userId == null || orderId == null || orderNo == null) {
            return;
        }
        try {
            String payload = buildPayload(orderId, orderNo, userId, statusText, extraInfo);

            // 1. 落本地消息（outbox）：崩溃/MQ 故障由 LocalMessageRetryJob 补发
            LocalMessage message = persistLocalMessage(userId, orderNo, payload);

            // 2. 提交后发送（有事务时防御）；无事务（当前调用点均无）→ 立即发送
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendAndMark(payload, message.getId(), orderNo, orderId, statusText);
                    }
                });
            } else {
                sendAndMark(payload, message.getId(), orderNo, orderId, statusText);
            }
        } catch (Exception e) {
            log.error("[订单通知] 处理异常(不影响交易主链路): orderId={}, status={}", orderId, statusText, e);
        }
    }

    /** 构建事件 JSON（与消费端 NotificationEventDTO 对齐；补发路径直接投该字符串，即时/补发字节一致） */
    private String buildPayload(Long orderId, String orderNo, Long userId, String statusText, String extraInfo) throws Exception {
        StringBuilder content = new StringBuilder("订单 ").append(orderNo).append(" ").append(statusText);
        if (extraInfo != null && !extraInfo.isEmpty()) {
            content.append("（").append(extraInfo).append("）");
        }

        Map<String, Object> event = new HashMap<>();
        event.put("type", TYPE_ORDER);
        event.put("targetUserId", userId);
        event.put("targetId", orderId);
        event.put("targetType", TARGET_TYPE_ORDER);
        event.put("targetName", orderNo);
        event.put("content", content.toString());
        Map<String, Object> extra = new HashMap<>();
        extra.put("orderId", orderId);
        extra.put("orderNo", orderNo);
        extra.put("status", statusText);
        event.put("extraData", objectMapper.writeValueAsString(extra));
        return objectMapper.writeValueAsString(event);
    }

    /** C-012 F6：事件落本地消息表（status=0 待发送；发送成功后 markSuccess） */
    private LocalMessage persistLocalMessage(Long userId, String orderNo, String payload) {
        LocalMessage message = new LocalMessage();
        message.setUserId(userId); // 分片键冗余存储（ShardingSphere 路由）
        message.setTransactionId(orderNo);
        message.setServiceName("order");
        message.setOperationType(OPERATION_TYPE_ORDER_STATUS_NOTIFICATION);
        message.setPayload(payload);
        message.setStatus(0);
        message.setRetryCount(0);
        message.setNextRetryTime(LocalDateTime.now());
        localMessageMapper.insert(message);
        return message;
    }

    /** 即时发送 + 成功标记（失败留库 → 补发 Job；本方法不抛异常） */
    private void sendAndMark(String payload, Long messageId, String orderNo, Long orderId, String statusText) {
        // 异步回调在 RocketMQ Netty 线程执行：捕获发送线程的 traceId，回调日志手动补 MDC
        String traceId = TraceContextHolder.getTraceId() != null
                ? TraceContextHolder.getTraceId()
                : org.slf4j.MDC.get("traceId");
        try {
            rocketMQTemplate.asyncSend(NOTIFICATION_TOPIC,
                    MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(payload).build()),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            try {
                                // 精确按行标记：同订单多状态共享 (orderNo, operationType)，
                                // 不能用 markSuccessByTransactionIdAndType 粗标（会误标尚未发送的其他状态行）
                                localMessageMapper.markSuccess(messageId);
                            } catch (Exception e) {
                                withTrace(traceId, () -> log.warn(
                                        "[订单通知] markSuccess 失败(补发 Job 将重复投递一次，聚合计数 +1 影响轻微): orderNo={}",
                                        orderNo, e));
                            }
                            withTrace(traceId, () -> log.info("[订单通知] 已发送: orderId={}, status={}", orderId, statusText));
                        }

                        @Override
                        public void onException(Throwable e) {
                            withTrace(traceId, () -> log.error(
                                    "[订单通知] 发送失败(留库补发，不影响交易主链路): orderId={}, status={}", orderId, statusText, e));
                        }
                    });
        } catch (Exception e) {
            log.warn("[订单通知] 即时发送异常(留库补发): orderNo={}, status={}", orderNo, statusText, e);
        }
    }

    /** 在 MQ Netty 回调线程中临时恢复发送线程的 traceId（不污染线程池复用） */
    private static void withTrace(String traceId, Runnable action) {
        if (traceId == null || traceId.isEmpty()) {
            action.run();
            return;
        }
        String prev = org.slf4j.MDC.get("traceId");
        org.slf4j.MDC.put("traceId", traceId);
        try {
            action.run();
        } finally {
            if (prev != null) {
                org.slf4j.MDC.put("traceId", prev);
            } else {
                org.slf4j.MDC.remove("traceId");
            }
        }
    }
}
