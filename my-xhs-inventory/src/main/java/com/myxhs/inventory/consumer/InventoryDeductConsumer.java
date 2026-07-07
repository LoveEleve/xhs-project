package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.event.InventoryDeductEvent;
import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 库存扣减消费者（L2：MQ 异步扣 MySQL）
 * <p>
 * 消费 INVENTORY_TOPIC 消息，将库存变更持久化到 MySQL。
 * 三种操作：
 * - PRE_DEDUCT：预扣减（available_stock → locked_stock）
 * - CONFIRM：确认扣减（locked_stock 减少）
 * - RELEASE：释放库存（locked_stock → available_stock）
 * </p>
 * <p>
 * 幂等保证（双重）：
 * 1. 消费者层面：MessageIdempotentHelper（msgId, 24h）快速去重，避免重复消费
 * 2. MySQL 使用乐观锁（WHERE locked_stock >= quantity / available_stock >= quantity）
 * 3. 最坏情况：重复扣减导致 MySQL 库存偏低，L3 对账修复会以 Redis 为准修正
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "INVENTORY_TOPIC",
        consumerGroup = "inventory-deduct-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class InventoryDeductConsumer implements RocketMQListener<MessageExt> {

    private final InventoryMapper inventoryMapper;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "inventory:deduct";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            // 统一幂等检查
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            String body = new String(msg.getBody());
            InventoryDeductEvent event = objectMapper.readValue(body, InventoryDeductEvent.class);

            log.info("[库存L2] 收到消息: action={}, orderId={}, skuId={}, qty={}",
                    event.getAction(), event.getOrderId(), event.getSkuId(), event.getQuantity());

            switch (event.getAction()) {
                case "PRE_DEDUCT" -> handlePreDeduct(event);
                case "CONFIRM" -> handleConfirm(event);
                case "RELEASE" -> handleRelease(event);
                default -> log.warn("[库存L2] 未知操作类型: {}", event.getAction());
            }
        } catch (Exception e) {
            log.error("[库存L2] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("库存消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 预扣减：available_stock 减少，locked_stock 增加
     * <p>
     * 使用乐观锁 WHERE available_stock >= quantity 防止扣成负数。
     * 【修复M7】乐观锁失败后退避重试最多 3 次，避免等 24h 对账才修复。
     * 重试失败后仍不抛异常，L3 对账兜底。
     * </p>
     */
    private void handlePreDeduct(InventoryDeductEvent event) {
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            int affected = inventoryMapper.deductStock(event.getSkuId(), event.getQuantity());
            if (affected > 0) {
                log.info("[库存L2] 预扣减MySQL成功: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 1);
                return;
            }
            // 乐观锁竞争失败，退避后重试
            if (i < maxRetry - 1) {
                try {
                    Thread.sleep(50L * (i + 1)); // 50ms, 100ms 退避
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("[库存L2] 预扣减MySQL重试: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 2);
            }
        }
        // 重试耗尽仍失败，等 L3 对账修复
        log.warn("[库存L2] 预扣减MySQL失败({}次重试后仍库存不足): skuId={}, qty={}（等待L3对账修复）",
                maxRetry, event.getSkuId(), event.getQuantity());
    }

    /**
     * 确认扣减：locked_stock 减少（库存正式扣除）
     */
    private void handleConfirm(InventoryDeductEvent event) {
        int affected = inventoryMapper.confirmDeduct(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 确认扣减MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 确认扣减MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }

    /**
     * 释放库存：locked_stock 回退到 available_stock
     */
    private void handleRelease(InventoryDeductEvent event) {
        int affected = inventoryMapper.releaseStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 释放库存MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 释放库存MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }
}
