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
 * 下单清车消费者（C-006）：消费 ORDER_TRANSACTION_TOPIC（订单创建事务消息/Outbox 补发）。
 * <p>
 * 仅 source=CART 触发清车（直接购买不碰购物车）；去重与竞态握手见 {@link CartOrderLinkageService}。
 * 解析风格对齐 inventory 的 OrderTransactionConsumer（JsonNode 按字段读 + Long 兼容字符串编码）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TRANSACTION_TOPIC",
        consumerGroup = "cart-order-created-group",
        maxReconsumeTimes = 3
)
public class OrderCreatedCartConsumer implements RocketMQListener<MessageExt> {

    private final CartOrderLinkageService linkageService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            JsonNode payload = objectMapper.readTree(msg.getBody());
            String orderNo = textOrNull(payload, "orderNo");
            Long userId = resolveLong(payload.get("userId"));
            JsonNode skuItems = payload.get("skuItems");
            if (orderNo == null || orderNo.isBlank() || userId == null
                    || skuItems == null || !skuItems.isArray() || skuItems.isEmpty()) {
                throw new IllegalArgumentException(
                        "订单创建消息缺少关键字段(orderNo/userId/skuItems): msgId=" + msg.getMsgId());
            }

            List<CartOrderLinkageService.LinkageItem> items = new ArrayList<>();
            for (JsonNode item : skuItems) {
                Long skuId = resolveLong(item.get("skuId"));
                if (skuId != null) {
                    Integer quantity = resolveInt(item.get("quantity"));
                    items.add(new CartOrderLinkageService.LinkageItem(skuId, quantity));
                }
            }

            linkageService.handleOrderCreated(orderNo, userId, textOrNull(payload, "source"), items);
        } catch (Exception e) {
            log.error("[购物车联动] 清车处理失败(触发重试): msgId={}", msg.getMsgId(), e);
            throw new IllegalStateException("购物车清车联动失败(可重试)", e);
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
