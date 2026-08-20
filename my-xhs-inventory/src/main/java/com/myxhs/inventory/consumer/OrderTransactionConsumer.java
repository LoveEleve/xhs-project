package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单事务消息消费者 — 下单成功后预扣库存
 * <p>
 * 消费 ORDER_TRANSACTION_TOPIC 消息（由 OrderTransactionListener Commit 后可见）。
 * 收到消息后对订单中的每个 SKU 执行预扣减。
 * </p>
 * <p>
 * 幂等保证（双重）：
 * 1. 消费者层面：MessageIdempotentHelper（msgId），RocketMQ at-least-once 重复投递时跳过
 * 2. InventoryService 层面：Lua 脚本内置幂等（PREDEDUCT_KEY 已存在则返回 -1）
 * </p>
 * <p>
 * 幂等键选择说明：
 * - 使用 msgId 而非 orderNo：避免 rebalance 时实例崩溃导致部分 SKU 永久跳过
 *   （旧实现：幂等标记在循环前设置，实例崩溃后新实例因 orderNo 幂等跳过全部 SKU）
 * - preDeduct() Lua 脚本以 pseudoOrderId 为幂等键，防止同一订单重复预扣
 * </p>
 * <p>
 * 失败策略：
 * - 抛出异常触发 RocketMQ 重试（指数退避，最多 5 次）
 * - 超过重试次数进死信队列，运维人工介入
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TRANSACTION_TOPIC",
        consumerGroup = "inventory-order-transaction-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class OrderTransactionConsumer implements RocketMQListener<MessageExt> {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "inventory:order:consumed";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(body);

            JsonNode orderNoNode = payload.get("orderNo");
            JsonNode userIdNode = payload.get("userId");
            JsonNode skuItems = payload.get("skuItems");
            if (orderNoNode == null || orderNoNode.isNull() || orderNoNode.asText().isBlank()
                    || userIdNode == null || userIdNode.isNull() || !userIdNode.canConvertToLong()
                    || skuItems == null || !skuItems.isArray() || skuItems.isEmpty()) {
                throw new IllegalArgumentException("订单事务消息缺少关键字段(orderNo/userId/skuItems)");
            }
            String orderNo = orderNoNode.asText();
            Long userId = userIdNode.asLong();

            // 消费者层面幂等校验：msgId 级别去重，避免 rebalance 时部分 SKU 永久跳过
            // 使用 msgId 而非 orderNo：orderNo 在循环内部分成功时会导致 rebalance 后新实例跳过
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            // 从 orderNo 派生伪 orderId（用作库存预扣幂等键）
            // T-067（2026-08-14 G5 执行发现）：原 fold-hash(*31+char) 与 OrderService.derivePseudoOrderId
            // 的 SHA-256 前 8 字节（P2-12 修复）不一致 → 预扣 key 与释放/确认 key 对不上 → 库存永远无法释放（泄漏）。
            // 统一为 SHA-256 前 8 字节（Big-endian signed long，与 OrderService 完全一致）。
            long pseudoOrderId;
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                long r = 0;
                for (int i = 0; i < 8; i++) {
                    r = (r << 8) | (digest[i] & 0xFF);
                }
                pseudoOrderId = r;
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }

            for (JsonNode item : skuItems) {
                JsonNode skuIdNode = item.get("skuId");
                JsonNode quantityNode = item.get("quantity");
                if (skuIdNode == null || !skuIdNode.canConvertToLong()
                        || quantityNode == null || !quantityNode.canConvertToInt()
                        || quantityNode.asInt() <= 0) {
                    throw new IllegalArgumentException("订单事务消息 skuItems 字段异常");
                }
                Long skuId = skuIdNode.asLong();
                int quantity = quantityNode.asInt();

                PreDeductRequest preDeductRequest = new PreDeductRequest();
                preDeductRequest.setOrderId(pseudoOrderId);
                preDeductRequest.setSkuId(skuId);
                preDeductRequest.setQuantity(quantity);
                preDeductRequest.setUserId(userId);

                try {
                    inventoryService.preDeduct(preDeductRequest);
                    log.info("[库存-事务消费] 预扣减成功: orderNo={}, skuId={}, qty={}",
                            orderNo, skuId, quantity);
                    } catch (com.myxhs.common.exception.BizException e) {
                        if ("SKU不存在".equals(e.getMessage())) {
                            log.error("[库存-事务消费] 不可恢复坏消息，SKU不存在，停止重试: orderNo={}, skuId={}, qty={}",
                                    orderNo, skuId, quantity, e);
                            return;
                        }
                        log.error("[库存-事务消费] 预扣减失败: orderNo={}, skuId={}, qty={}",
                                orderNo, skuId, quantity, e);
                        throw new RuntimeException("库存预扣减失败: skuId=" + skuId, e);
                    } catch (Exception e) {
                        log.error("[库存-事务消费] 预扣减失败: orderNo={}, skuId={}, qty={}",
                                orderNo, skuId, quantity, e);
                        throw new RuntimeException("库存预扣减失败: skuId=" + skuId, e);
                    }
            }

            log.info("[库存-事务消费] 订单库存预扣减完成: orderNo={}, userId={}", orderNo, userId);

        } catch (RuntimeException e) {
            // 失败时移除幂等标记，允许 MQ 重投重新处理（否则重投被 isFirstProcess=false 跳过，
            // 部分 SKU 永久不预扣 → 超卖）。preDeduct Lua 幂等保证重复处理安全。
            idempotentHelper.removeMark(BIZ_TYPE, msg.getMsgId());
            throw e; // 直接抛出触发 MQ 重试
        } catch (Exception e) {
            log.error("[库存-事务消费] 消费失败: msgId={}", msg.getMsgId(), e);
            idempotentHelper.removeMark(BIZ_TYPE, msg.getMsgId());
            throw new RuntimeException("订单事务消息消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
