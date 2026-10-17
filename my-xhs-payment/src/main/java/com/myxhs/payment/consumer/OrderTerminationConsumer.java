package com.myxhs.payment.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单终结消费者（ITER-002 阶段⑤ / 06 §七 阶段 3）
 * <p>
 * 订阅订单终结事件（04B 事务消息链路，与 inventory/coupon/notification 消费者同构）：
 * 关闭该订单"活着的"（pending）支付单（status=4 已关闭 + 渠道 close），
 * 消灭"关单后用户仍可支付旧渠道交易"的窗口（prepay_id 有效期内可付 → 撞车退款链）。
 * </p>
 * <p>
 * 幂等：消息去重（KEYS/msgId，24h）+ closePendingOnTermination 自身幂等
 * （无 pending → no-op；乐观锁条件更新）。消费失败抛异常触发重试（最多 5 次后 DLQ）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TERMINATION_TOPIC",
        consumerGroup = "payment-order-termination-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class OrderTerminationConsumer implements RocketMQListener<MessageExt> {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "payment:order-termination:consumed";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(body);
            JsonNode orderIdNode = payload.get("orderId");
            if (orderIdNode == null || orderIdNode.isNull()) {
                throw new IllegalArgumentException("订单终结消息缺少关键字段(orderId)");
            }
            Long orderId = orderIdNode.asLong();

            String dedupKey = resolveDedupKey(msg);
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            paymentService.closePendingOnTermination(orderId);
        } catch (Exception e) {
            log.error("[支付-订单终结] 消费失败(触发重试): msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("订单终结事件处理失败: " + e.getMessage(), e);
        }
    }

    private String resolveDedupKey(MessageExt msg) {
        String keys = msg.getKeys();
        return (keys != null && !keys.isBlank()) ? keys : msg.getMsgId();
    }
}
