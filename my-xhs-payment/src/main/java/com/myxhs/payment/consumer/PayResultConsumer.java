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
 * 支付结果消费者
 * <p>
 * 订单服务消费此消息后更新订单状态为"已支付"。
 * 此消费者仅消费来自支付模块内部发出的支付结果消息。
 * </p>
 * <p>
 * 注意：此消费者属于支付服务模块，消费的是支付服务自己发出的 MQ 消息。
 * 但实际业务中，订单服务也会订阅 PAY_RESULT_TOPIC 来更新订单状态。
 * 这里保留此消费者主要是为了记录日志和补偿处理。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "PAY_RESULT_TOPIC",
        consumerGroup = "payment-pay-result-consumer-group"
)
public class PayResultConsumer implements RocketMQListener<MessageExt> {

    private final PaymentService paymentService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody());
            log.info("[支付结果消费] 收到消息: {}", body);
            // 此处可做补偿逻辑：如支付成功但订单未更新，可主动通知订单服务
        } catch (Exception e) {
            log.error("[支付结果消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("支付结果消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
