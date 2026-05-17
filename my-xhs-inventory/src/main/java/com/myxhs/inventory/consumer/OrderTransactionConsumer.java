package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 订单事务消息消费者 — 下单成功后预扣库存
 * <p>
 * 消费 ORDER_TRANSACTION_TOPIC 消息（由 OrderTransactionListener Commit 后可见）。
 * 收到消息后对订单中的每个 SKU 执行预扣减。
 * </p>
 * <p>
 * 幂等保证（双重）：
 * 1. 消费者层面：orderNo + Redis SET NX（24h 过期），重复消费直接跳过
 * 2. InventoryService 层面：Lua 脚本内置幂等（PREDEDUCT_KEY 已存在则返回 -1）
 * </p>
 * <p>
 * 失败策略：
 * - 抛出异常触发 RocketMQ 重试（指数退避，最多 16 次）
 * - 超过重试次数进死信队列，运维人工介入
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TRANSACTION_TOPIC",
        consumerGroup = "inventory-order-transaction-consumer-group",
        selectorExpression = "*"
)
public class OrderTransactionConsumer implements RocketMQListener<MessageExt> {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String IDEMPOTENT_PREFIX = "inventory:order:consumed:";

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(body);

            String orderNo = payload.get("orderNo").asText();
            Long userId = payload.get("userId").asLong();

            // 消费者层面幂等校验：同一个 orderNo 只消费一次
            String idempotentKey = IDEMPOTENT_PREFIX + orderNo;
            Boolean firstTime = stringRedisTemplate.opsForValue()
                    .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
            if (Boolean.FALSE.equals(firstTime)) {
                log.info("[库存-事务消费] 幂等跳过: orderNo={}", orderNo);
                return;
            }

            // 用 orderNo 的 hashCode 作为 orderId（用于库存预扣的幂等键）
            // 注意：Math.abs(Integer.MIN_VALUE) 仍为负数（整数溢出），
            // 所以用位运算 & 0x7FFFFFFF 保证非负
            long pseudoOrderId = orderNo.hashCode() & 0x7FFFFFFF;

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
                        // 预扣减失败，删除消费者幂等键允许 MQ 重试
                        stringRedisTemplate.delete(idempotentKey);
                        log.error("[库存-事务消费] 预扣减失败: orderNo={}, skuId={}, qty={}",
                                orderNo, skuId, quantity, e);
                        throw new RuntimeException("库存预扣减失败: skuId=" + skuId, e);
                    }
                }
            }

            log.info("[库存-事务消费] 订单库存预扣减完成: orderNo={}, userId={}", orderNo, userId);

        } catch (RuntimeException e) {
            throw e; // 直接抛出触发 MQ 重试
        } catch (Exception e) {
            log.error("[库存-事务消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("订单事务消息消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
