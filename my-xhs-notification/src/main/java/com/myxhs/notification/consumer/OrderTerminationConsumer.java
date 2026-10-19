package com.myxhs.notification.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单终结事件消费者 — 用户通知（04B）
 * <p>
 * 消费 ORDER_TERMINATION_TOPIC（Outbox 投递：即时发送 + LocalMessageRetryJob 兜底补发），
 * 按终结原因（USER_CANCELLED/TIMEOUT/INVENTORY_FAILED/COUPON_FAILED）生成 type=5 订单通知，
 * 复用通知管道（模板 + 聚合窗口锁 + SSE + 未读计数）。
 * </p>
 * <p>
 * 幂等保证（两级）：
 * 1. 消息 KEYS（=orderNo）去重：跨重投稳定（Outbox 补发换新 msgId，msgId 去重会失效）
 * 2. 聚合器窗口锁（processEvent 内部）——同一窗口内不重复创建
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TERMINATION_TOPIC",
        consumerGroup = "notification-order-termination-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 3
)
public class OrderTerminationConsumer implements RocketMQListener<MessageExt> {

    private final NotificationService notificationService;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "myxhs:notification:termination-consumed";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时
    /** 2=评论 3=关注 4=系统 5=订单（与 NotificationType 对齐） */
    private static final int TYPE_ORDER = 5;
    /** 目标类型：1-笔记 2-商品 3-订单（与 NotificationEventDTO 对齐） */
    private static final int TARGET_TYPE_ORDER = 3;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject event = JSON.parseObject(body);
            if (event == null) {
                log.warn("[通知-终结消费] 消息体为空: msgId={}", msg.getMsgId());
                return;
            }
            Long userId = event.getLong("userId");
            Long orderId = event.getLong("orderId");
            String orderNo = event.getString("orderNo");
            String reason = event.getString("reason");
            if (userId == null || orderId == null) {
                log.warn("[通知-终结消费] 消息缺少关键字段(userId/orderId): msgId={}, body={}",
                        msg.getMsgId(), body);
                return;
            }

            String dedupKey = resolveDedupKey(msg);
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            NotificationEventDTO dto = NotificationEventDTO.builder()
                    .type(TYPE_ORDER)
                    .targetUserId(userId)
                    .targetId(orderId)
                    .targetType(TARGET_TYPE_ORDER)
                    .targetName(orderNo)
                    .content(buildContent(orderNo, reason))
                    .extraData(buildExtraData(orderId, orderNo, reason))
                    .build();
            notificationService.processEvent(dto);
            log.info("[通知-终结消费] 通知已生成: userId={}, orderId={}, reason={}", userId, orderId, reason);
        } catch (Exception e) {
            log.error("[通知-终结消费] 处理失败: msgId={}", msg.getMsgId(), e);
            // 删除幂等标记，让 MQ 重试时能真正重新处理（而非被 isFirstProcess 拦截）
            try {
                idempotentHelper.removeMark(BIZ_TYPE, resolveDedupKey(msg));
            } catch (Exception markEx) {
                log.error("[通知-终结消费] 清除幂等标记失败: msgId={}", msg.getMsgId(), markEx);
            }
            throw new RuntimeException("订单终结通知消费失败", e); // 触发 RocketMQ 重试
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /** 按终结原因生成用户文案（风格对齐 OrderNotificationPublisher.publishStatusChanged） */
    private String buildContent(String orderNo, String reason) {
        String statusText = switch (reason == null ? "" : reason) {
            case "USER_CANCELLED" -> "已取消（如已支付将自动退款）";
            case "TIMEOUT" -> "超时未支付，已自动关闭";
            case "INVENTORY_FAILED" -> "因商品库存不足已自动取消";
            case "COUPON_FAILED" -> "因优惠券核销失败已自动取消";
            case "PAY_FAILED" -> "因支付失败已自动取消";
            default -> "已关闭";
        };
        return "订单 " + orderNo + " " + statusText;
    }

    /** 扩展数据（对齐 OrderNotificationPublisher 的 extraData 形态，供模板/跳转使用） */
    private String buildExtraData(Long orderId, String orderNo, String reason) {
        JSONObject extra = new JSONObject();
        extra.put("orderId", orderId);
        extra.put("orderNo", orderNo);
        extra.put("reason", reason);
        return extra.toJSONString();
    }

    /** 幂等键：优先消息 KEYS（=orderNo，跨重投稳定）；无 KEYS 时退化为"原 msgId 属性"（C-012 F2）→ broker msgId */
    private String resolveDedupKey(MessageExt msg) {
        String keys = msg.getKeys();
        if (keys != null && !keys.isBlank()) {
            return keys;
        }
        String original = msg.getProperty(NotificationDlqReplayConsumer.ORIGINAL_MSG_ID_PROPERTY);
        return (original != null && !original.isBlank()) ? original : msg.getMsgId();
    }
}
