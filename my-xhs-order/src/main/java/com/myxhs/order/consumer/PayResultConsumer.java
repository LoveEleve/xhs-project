package com.myxhs.order.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 支付结果消费者（PAY_RESULT_TOPIC）
 * <p>
 * 支付服务在「支付成功/失败后 Feign 直调 order 失败或返回 503」时发送此消息。
 * 本消费者作为 MQ 即时兜底通道：Feign 同步失败时消费消息收敛订单状态，
 * 最终兜底为 XXL-Job paymentNotifyCompensateJob（每 2 分钟扫描重发）。
 * </p>
 * <p>
 * 幂等：orderService.onPaymentSuccess/onPaymentFailed 内部乐观锁
 * （WHERE status=当前状态），重复通知时状态已流转直接返回，天然幂等，无需额外去重。
 * </p>
 * <p>
 * 重试：maxReconsumeTimes=3；业务拒绝（订单已非待付款）不抛异常正常消费，
 * 仅解析/执行异常抛 RuntimeException 触发重试，耗尽后进 DLQ。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "PAY_RESULT_TOPIC",
        consumerGroup = "order-pay-result-consumer-group",
        maxReconsumeTimes = 3
)
public class PayResultConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            log.info("[支付结果消费] 收到消息: msgId={}, body={}", msg.getMsgId(), body);

            var map = JSON.parseObject(body, java.util.Map.class);
            if (map == null || map.get("orderId") == null) {
                log.warn("[支付结果消费] 消息体缺少orderId: msgId={}", msg.getMsgId());
                return;
            }
            Long orderId = ((Number) map.get("orderId")).longValue();
            Long userId = map.get("userId") != null ? ((Number) map.get("userId")).longValue() : null;
            boolean success = Boolean.TRUE.equals(map.get("success"));

            if (success) {
                boolean updated = orderService.onPaymentSuccess(orderId, userId);
                if (!updated) {
                    // 订单已非待付款（已支付/已取消/已关闭）：属竞态正常终态，不重试
                    log.info("[支付结果消费] 订单已非待付款(竞态正常): orderId={}", orderId);
                }
            } else {
                orderService.onPaymentFailed(orderId);
            }
        } catch (Exception e) {
            log.error("[支付结果消费] 处理失败: msgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new RuntimeException("支付结果消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
