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
 * 退款结果消费者（REFUND_RESULT_TOPIC）
 * <p>
 * 支付服务在「全额退款成功后 Feign 直调 order 失败或返回 503」时发送此消息。
 * 本消费者作为 MQ 即时兜底通道：Feign 同步失败时消费消息收敛订单状态（已退款+释放库存+退券），
 * 最终兜底为 XXL-Job refundNotifyCompensateJob（每 3 分钟扫描重发）。
 * </p>
 * <p>
 * 幂等：orderService.onRefundSuccess 内部乐观锁（WHERE status=已支付），重复通知幂等。
 * 退款失败（success=false）不改变订单状态（保持已支付为正确终态），仅记录日志供对账，
 * payment 侧退款单已置失败/关闭，用户可重新发起退款。
 * </p>
 * <p>
 * 重试：maxReconsumeTimes=3；业务拒绝（订单已非已支付）不抛异常正常消费，
 * 仅解析/执行异常抛 RuntimeException 触发重试，耗尽后进 DLQ。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "REFUND_RESULT_TOPIC",
        consumerGroup = "order-refund-result-consumer-group",
        maxReconsumeTimes = 3
)
public class RefundResultConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            log.info("[退款结果消费] 收到消息: msgId={}, body={}", msg.getMsgId(), body);

            var map = JSON.parseObject(body, java.util.Map.class);
            if (map == null || map.get("orderId") == null) {
                log.warn("[退款结果消费] 消息体缺少orderId: msgId={}", msg.getMsgId());
                return;
            }
            Long orderId = ((Number) map.get("orderId")).longValue();
            boolean success = Boolean.TRUE.equals(map.get("success"));

            if (success) {
                boolean updated = orderService.onRefundSuccess(orderId);
                if (!updated) {
                    // 订单已非已支付（已退款/已取消等）：竞态正常终态，不重试
                    log.info("[退款结果消费] 订单已非已支付(竞态正常): orderId={}", orderId);
                }
            } else {
                // 退款失败/关闭：订单保持已支付是正确终态，payment 侧退款单已失败/关闭，用户可重试退款
                log.warn("[退款结果消费] 退款失败/关闭，订单保持已支付: orderId={}", orderId);
            }
        } catch (Exception e) {
            log.error("[退款结果消费] 处理失败: msgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new RuntimeException("退款结果消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
