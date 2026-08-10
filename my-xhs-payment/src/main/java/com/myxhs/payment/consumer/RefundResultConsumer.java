package com.myxhs.payment.consumer;

import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 退款结果消费者
 * <p>
 * 消费退款结果消息，触发退款成功后的联动操作（如库存回补、优惠券退还等）。
 * 订单服务也会订阅 REFUND_RESULT_TOPIC 来更新订单状态。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "REFUND_RESULT_TOPIC",
        consumerGroup = "payment-refund-result-consumer-group",
        maxReconsumeTimes = 5
)
public class RefundResultConsumer implements RocketMQListener<MessageExt> {

    private final PaymentService paymentService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            byte[] bodyBytes = msg.getBody();
            if (bodyBytes == null) {
                log.warn("[退款结果消费] 消息体为null: msgId={}", msg.getMsgId());
                return;
            }
            String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.info("[退款结果消费] 收到消息: {}", body);
            // 此处可做补偿逻辑
        } catch (Exception e) {
            log.error("[退款结果消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("退款结果消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
