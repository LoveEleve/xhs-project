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
 * 幂等保证（三重）：
 * 1. 消息 KEYS（=orderNo）去重：跨重投稳定（Outbox 补发换新 msgId，msgId 去重会失效）
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
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            // 从 orderNo 派生伪 orderId（必须与创建侧预扣时完全一致，否则释放的 key 对不上；
            // 共用 PseudoOrderIdUtil 保证算法单点维护）
            long pseudoOrderId = PseudoOrderIdUtil.derive(orderNo);
            ReleaseStockRequest request = new ReleaseStockRequest();
            request.setOrderId(pseudoOrderId);

            // releaseStock 内在幂等（终结墓碑 + 无记录即返回），重复消费安全
            inventoryService.releaseStock(request);
            log.info("[库存-终结消费] 预扣释放完成: orderNo={}, pseudoOrderId={}", orderNo, pseudoOrderId);
        } catch (RuntimeException e) {
            // 失败时移除幂等标记，允许 MQ 重投重新处理
            idempotentHelper.removeMark(BIZ_TYPE, resolveDedupKey(msg));
            throw e; // 直接抛出触发 MQ 重试
        } catch (Exception e) {
            log.error("[库存-终结消费] 消费失败: msgId={}", msg.getMsgId(), e);
            idempotentHelper.removeMark(BIZ_TYPE, resolveDedupKey(msg));
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
}
