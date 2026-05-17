package com.myxhs.order.consumer;

import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.repository.OrderNoMappingRepository;
import com.myxhs.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 超时关单消费者（延时消息触发）
 * <p>
 * 消费 ORDER_CLOSE_TOPIC 延时消息（30 分钟后到达）。
 * 只关"待付款"状态的订单，已支付/已取消的不处理（幂等）。
 * </p>
 * <p>
 * 分库分表适配：通过消息头中的 userId 或映射表获取 user_id，
 * 确保 closeTimeoutOrder 能正确路由到分片库。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_CLOSE_TOPIC",
        consumerGroup = "order-close-consumer-group"
)
public class OrderCloseConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;
    private final OrderNoMappingRepository orderNoMappingRepository;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody());
            Long orderId = Long.parseLong(body.trim());

            // 从消息属性中获取 userId（发送时写入）
            String userIdStr = msg.getUserProperty("userId");
            Long userId;
            if (userIdStr != null) {
                userId = Long.parseLong(userIdStr);
            } else {
                // 兜底：通过映射表查询
                OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
                if (mapping == null) {
                    log.error("[订单关单] 无法获取userId: orderId={}", orderId);
                    return;
                }
                userId = mapping.getUserId();
            }

            log.info("[订单关单] 收到延时消息: orderId={}, userId={}", orderId, userId);
            orderService.closeTimeoutOrder(orderId, userId);

        } catch (Exception e) {
            log.error("[订单关单] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("关单消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
