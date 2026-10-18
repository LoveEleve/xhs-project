package com.myxhs.cart.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.service.CartOrderLinkageService;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 取消回补消费者（C-006）：消费 ORDER_TERMINATION_TOPIC（订单终结事务消息/Outbox 补发）。
 * <p>
 * 五类终结原因（用户取消/超时/库存失败/券失败/支付失败）全部触发回补；
 * 旧版事件（eventVersion=1，无 items）由联动服务安全跳过。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TERMINATION_TOPIC",
        consumerGroup = "cart-order-termination-group",
        maxReconsumeTimes = 3
)
public class OrderTerminationCartConsumer implements RocketMQListener<MessageExt> {

    private final CartOrderLinkageService linkageService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            JsonNode payload = objectMapper.readTree(msg.getBody());
            String orderNo = textOrNull(payload, "orderNo");
            Long userId = resolveLong(payload.get("userId"));
            if (orderNo == null || orderNo.isBlank() || userId == null) {
                throw new IllegalArgumentException(
                        "订单终结消息缺少关键字段(orderNo/userId): msgId=" + msg.getMsgId());
            }

            // items 允许缺失（eventVersion=1 旧事件）——联动服务按"无 items 跳过"处理
            List<CartOrderLinkageService.LinkageItem> items = new ArrayList<>();
            JsonNode itemsNode = payload.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    Long skuId = resolveLong(item.get("skuId"));
                    if (skuId != null) {
                        items.add(new CartOrderLinkageService.LinkageItem(skuId, resolveInt(item.get("quantity"))));
                    }
                }
            }

            linkageService.handleOrderTerminated(orderNo, userId, items);
        } catch (Exception e) {
            log.error("[购物车联动] 回补处理失败(触发重试): msgId={}", msg.getMsgId(), e);
            throw new IllegalStateException("购物车回补联动失败(可重试)", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** JacksonConfig 全局 Long→String（防 JS 精度丢失），兼容字符串/数字两种编码 */
    private Long resolveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer resolveInt(JsonNode node) {
        Long value = resolveLong(node);
        return value == null ? null : value.intValue();
    }
}
