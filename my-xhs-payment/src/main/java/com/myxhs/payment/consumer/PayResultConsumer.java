package com.myxhs.payment.consumer;

import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付结果消费者
 * <p>
 * 订单服务消费此消息后更新订单状态为"已支付"。
 * 此消费者仅消费来自支付模块内部发出的支付结果消息。
 * </p>
 * <p>
 * 注意：支付成功的正式业务通知链已经切到 Feign 同步 + 支付侧补偿任务。
 * 该消费者默认关闭，只有在明确要把 PAY_RESULT_TOPIC 作为支付侧自消费补偿链时才启用。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "myxhs.payment.mq.pay-result-consumer.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "PAY_RESULT_TOPIC",
        consumerGroup = "payment-pay-result-consumer-group",
        maxReconsumeTimes = 5
)
public class PayResultConsumer implements RocketMQListener<MessageExt> {

    private final PaymentService paymentService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            byte[] bodyBytes = msg.getBody();
            if (bodyBytes == null) {
                log.warn("[支付结果消费] 消息体为null: msgId={}", msg.getMsgId());
                return;
            }
            String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.info("[支付结果消费] 收到消息: {}", body);
            // 注意：当前支付结果通知走 Feign 同步路径（notifyPaySuccess），
            // 此 MQ 消费者暂未实现业务逻辑，仅作为预留/日志记录。
            // 如需 MQ 补偿，需在此处补全 notifyPaySuccess 调用。
        } catch (Exception e) {
            log.error("[支付结果消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("支付结果消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
