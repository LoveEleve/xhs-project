package com.myxhs.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 库存失败事件消费者（订单侧收口）
 * <p>
 * 场景：库存预扣/确认最终失败（重试耗尽）时，inventory 发布 INVENTORY_FAILED_TOPIC。
 * 收口策略（详见订单状态机与 runbook）：
 * - 待付款(0)：直接取消（原因=库存预扣失败），不等 30 分钟超时关单；
 * - 已付款(1)：记 ERROR + 指标（人工/对账介入；发货守卫为最后一道闸）；
 * - 其他状态：幂等跳过。
 * </p>
 * <p>
 * 分库分表适配：消息只有 orderId，通过映射表解析 userId 以路由分片。
 * 映射缺失时不重试（补录任务每 5 分钟兜底；未支付单仍会被 30 分钟超时关单收敛）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "INVENTORY_FAILED_TOPIC",
        consumerGroup = "order-inventory-failed-consumer-group",
        maxReconsumeTimes = 3
)
public class InventoryFailureConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;
    private final OrderNoMappingRepository orderNoMappingRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(body, Map.class);

            Long orderId = toLong(event.get("orderId"));
            Long skuId = toLong(event.get("skuId"));
            String reason = event.get("reason") == null ? "UNKNOWN" : String.valueOf(event.get("reason"));
            if (orderId == null) {
                log.error("[库存失败收口] 消息缺少 orderId: body={}", body);
                return;
            }

            OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
            if (mapping == null) {
                log.error("[库存失败收口] 映射缺失无法路由(依赖补录任务/30分钟关单兜底): orderId={}, reason={}",
                        orderId, reason);
                return;
            }

            orderService.handleInventoryFailure(orderId, mapping.getUserId(), reason, skuId);
        } catch (Exception e) {
            log.error("[库存失败收口] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("库存失败事件处理失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
