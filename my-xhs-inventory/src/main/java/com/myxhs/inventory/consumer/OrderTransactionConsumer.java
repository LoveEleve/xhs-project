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

            // 坏消息防御：缺 orderNo/userId 时直接跳过不重试（NPE 重试 5 次进 DLQ 无意义）
            JsonNode orderNoNode = payload.get("orderNo");
            JsonNode userIdNode = payload.get("userId");
            if (orderNoNode == null || userIdNode == null) {
                log.warn("[库存-事务消费] 消息缺少必填字段(orderNo/userId), 跳过不重试: msgId={}, body={}",
                        msg.getMsgId(), body);
                return;
            }
            String orderNo = orderNoNode.asText();
            Long userId = userIdNode.asLong();

            // 消费者层面幂等校验：msgId 级别去重，避免 rebalance 时部分 SKU 永久跳过
            // 使用 msgId 而非 orderNo：orderNo 在循环内部分成功时会导致 rebalance 后新实例跳过
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            // 从 orderNo 派生伪 orderId（用作库存预扣幂等键）
            // 原实现：orderNo.hashCode() & 0x7FFFFFFF（仅 31 bit，1 万订单碰撞~2%）
            // 修复：多重乘法折叠为 63 bit，有效空间 ~2^50，碰撞概率逼近零
            long pseudoOrderId = 0;
            for (int i = 0; i < orderNo.length(); i++) {
                pseudoOrderId = pseudoOrderId * 31 + orderNo.charAt(i);
            }

            // 对每个 SKU 执行预扣减
            JsonNode skuItems = payload.get("skuItems");
            if (skuItems != null && skuItems.isArray()) {
                for (JsonNode item : skuItems) {
                    Long skuId = item.get("skuId").asLong();
                    int quantity = item.get("quantity").asInt();

                    PreDeductRequest preDeductRequest = new PreDeductRequest();
                    preDeductRequest.setOrderId(pseudoOrderId);
                    preDeductRequest.setSkuId(skuId);
                    preDeductRequest.setQuantity(quantity);
                    preDeductRequest.setUserId(userId);

                    try {
                        inventoryService.preDeduct(preDeductRequest);
                        log.info("[库存-事务消费] 预扣减成功: orderNo={}, skuId={}, qty={}",
                                orderNo, skuId, quantity);
                    } catch (Exception e) {
                        // 预扣减失败，抛出异常触发 RocketMQ 重试
                        // msgId 级别幂等确保 rebalance 后不会跳过未处理的 SKU
                        log.error("[库存-事务消费] 预扣减失败: orderNo={}, skuId={}, qty={}",
                                orderNo, skuId, quantity, e);
                        throw new RuntimeException("库存预扣减失败: skuId=" + skuId, e);
                    }
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
