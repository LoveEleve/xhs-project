package com.myxhs.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单状态变更通知发布器（type=5 订单通知，消费端 NotificationEventConsumer）
 * <p>
 * 背景：通知管道（NOTIFICATION_TOPIC + 模板 + SSE + 未读计数）早已具备，但订单域此前未接入，
 * 用户收不到"支付成功/已发货/退款到账"站内通知。本类补齐订单域生产者。
 * </p>
 * <p>
 * 可靠性口径：
 * - 仅在状态机真实流转成功后调用（乐观锁保证不重复），因此不做业务级去重；
 * - MQ 异步发送 + try/catch，通知失败不影响交易主链路；
 * - 消费端有 msgId 幂等 + 聚合窗口锁兜底。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationPublisher {

    private static final String NOTIFICATION_TOPIC = "NOTIFICATION_TOPIC";
    /** 2=评论 3=关注 4=系统 5=订单（与 NotificationType 对齐） */
    private static final int TYPE_ORDER = 5;
    /** 目标类型：1-笔记 2-商品 3-订单（与 NotificationEventDTO 对齐） */
    private static final int TARGET_TYPE_ORDER = 3;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布订单状态变更通知
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

            rocketMQTemplate.asyncSend(NOTIFICATION_TOPIC,
                    MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(event).build()),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.info("[订单通知] 已发送: orderId={}, status={}", orderId, statusText);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("[订单通知] 发送失败(不影响交易主链路): orderId={}, status={}", orderId, statusText, e);
                        }
                    });
        } catch (Exception e) {
            log.error("[订单通知] 发送异常(不影响交易主链路): orderId={}, status={}", orderId, statusText, e);
        }
    }
}
