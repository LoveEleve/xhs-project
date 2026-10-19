package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.service.InventoryService;
import com.myxhs.inventory.util.PseudoOrderIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单终结事件消费者 — 释放预扣库存（04B）
 * <p>
 * 消费 ORDER_TERMINATION_TOPIC（Outbox 投递：即时发送 + LocalMessageRetryJob 兜底补发）。
 * 收到消息后按 orderNo 派生伪订单ID，调用 releaseStock 释放预扣 + 写终结墓碑。
 * </p>
 * <p>
 * 幂等保证（C-008 F4 完成态语义）：
 * 1. 消息 KEYS（=orderNo）完成态标记：跨重投稳定（Outbox 补发换新 msgId）；处理成功后置位，
 *    处理中崩溃无标记 → 重投重新释放（releaseStock 本身幂等）
 * 2. releaseStock 终结墓碑：原子「检查记录 + 总是写墓碑」，重复投递安全
 * 3. 终结墓碑 + prededuct.lua 前置检查：拦截释放完成后迟到/重投的预扣消息（防泄漏）
 * </p>
 * <p>
 * 失败策略：抛异常触发 RocketMQ 重试（指数退避，最多 5 次），超过进死信人工介入。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TERMINATION_TOPIC",
        consumerGroup = "inventory-order-termination-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class OrderTerminationConsumer implements RocketMQListener<MessageExt> {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "inventory:order-termination:consumed";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(body);

            JsonNode orderNoNode = payload.get("orderNo");
            JsonNode userIdNode = payload.get("userId");
            if (orderNoNode == null || orderNoNode.isNull() || orderNoNode.asText().isBlank()
                    || userIdNode == null || userIdNode.isNull()) {
                throw new IllegalArgumentException("订单终结消息缺少关键字段(orderNo/userId)");
            }
            String orderNo = orderNoNode.asText();

            String dedupKey = resolveDedupKey(msg);
            // C-008 F4 完成态标记：只读检查（不置位）——处理中崩溃无标记，重投重新释放（releaseStock 幂等）
            if (idempotentHelper.isProcessed(BIZ_TYPE, dedupKey)) {
                log.info("[库存-终结消费] 已完成处理(重投跳过): dedupKey={}", dedupKey);
                return;
            }

            // 从 orderNo 派生伪 orderId（必须与创建侧预扣时完全一致，否则释放的 key 对不上；
            // 共用 PseudoOrderIdUtil 保证算法单点维护）
            long pseudoOrderId = PseudoOrderIdUtil.derive(orderNo);
            ReleaseStockRequest request = new ReleaseStockRequest();
            request.setOrderId(pseudoOrderId);
            // C-008：解析 items（订单明细事实源；inventory 侧按 per-(sku,order) 记录释放）
            request.setItems(parseItems(payload));

            // releaseStock 内在幂等（终结墓碑 + 无记录即返回），重复消费安全
            inventoryService.releaseStock(request);
            // C-008 F4：完成后置标记（失败不置标记 → 重投重新释放；releaseStock 幂等安全）
            idempotentHelper.markProcessed(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS);
            log.info("[库存-终结消费] 预扣释放完成: orderNo={}, pseudoOrderId={}", orderNo, pseudoOrderId);
        } catch (RuntimeException e) {
            throw e; // 直接抛出触发 MQ 重试（完成态语义：失败无标记，重投可重新处理）
        } catch (Exception e) {
            log.error("[库存-终结消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("订单终结消息消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /** 幂等键：优先消息 KEYS（=orderNo，跨重投稳定）；无 KEYS 时退化为 msgId */
    private String resolveDedupKey(MessageExt msg) {
        String keys = msg.getKeys();
        return (keys != null && !keys.isBlank()) ? keys : msg.getMsgId();
    }

    /** C-008：解析载荷 items（兼容数字/字符串形态的 Long——全局 JacksonConfig 把 Long 序列化为 String） */
    private java.util.List<com.myxhs.inventory.dto.request.StockItem> parseItems(JsonNode payload) {
        java.util.List<com.myxhs.inventory.dto.request.StockItem> items = new java.util.ArrayList<>();
        JsonNode itemsNode = payload.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return items;
        }
        for (JsonNode itemNode : itemsNode) {
            Long skuId = parseLongNode(itemNode.get("skuId"));
            if (skuId == null) {
                continue;
            }
            com.myxhs.inventory.dto.request.StockItem item = new com.myxhs.inventory.dto.request.StockItem();
            item.setSkuId(skuId);
            JsonNode qtyNode = itemNode.get("quantity");
            item.setQuantity(qtyNode == null || qtyNode.isNull() ? null : qtyNode.asInt());
            items.add(item);
        }
        return items;
    }

    private static Long parseLongNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
