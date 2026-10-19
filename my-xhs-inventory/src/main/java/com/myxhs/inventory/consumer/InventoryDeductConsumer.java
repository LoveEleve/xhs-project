package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.event.InventoryDeductEvent;
import com.myxhs.inventory.mapper.InventoryMapper;
import com.myxhs.inventory.service.InventoryL2InboxService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 库存扣减消费者（L2：MQ 异步扣 MySQL）
 * <p>
 * 消费 INVENTORY_TOPIC 消息，将库存变更持久化到 MySQL。
 * 四种操作：
 * - PRE_DEDUCT：预扣减（available_stock → locked_stock）
 * - CONFIRM：确认扣减（locked_stock 减少）
 * - RELEASE：释放库存（locked_stock → available_stock）
 * - REFUND_RESTORE：退款回补（available_stock +qty）
 * </p>
 * <p>
 * 幂等保证（C-008 F4：事务内幂等 + 完成态版本门）：
 * 1. 逻辑事件幂等：t_inventory_l2_event INSERT IGNORE 与库存变更**同事务**——
 *    补发（新 msgId）/重投/崩溃重投均不双扣、不丢（旧"前置 msgId 标记"崩溃后会把重投拦死）
 * 2. MySQL 使用乐观锁（WHERE locked_stock >= quantity / available_stock >= quantity）
 * 3. 版本门"完成后推进"（只读检查旧事件跳过，不再前置占位）——跨 action 乱序防护保留
 * 4. 最坏情况：条件失败导致 MySQL 镜像偏差，L3 对账以 Redis 为准修正
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
    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;
    private final InventoryL2InboxService inboxService;

    private static final String EVENT_VERSION_PREFIX = "inventory:event:version:";
    private static final long EVENT_VERSION_TTL_SECONDS = 86400; // 24 小时

    /** 库存失败事件 Topic（订单侧收口：未支付自动关单 / 已支付人工介入） */
    public static final String INVENTORY_FAILED_TOPIC = "INVENTORY_FAILED_TOPIC";

    /**
     * 版本单调推进（C-008：只增不减）——仅在事件处理完成后调用；
     * 并发乱序时旧值不覆盖新值（崩溃后重投靠 inbox 幂等 + 本步收敛）
     */
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> versionAdvanceScript
            = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local cur = tonumber(redis.call('get', KEYS[1]) or '0') " +
            "local v = tonumber(ARGV[1]) " +
            "if cur < v then redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) end " +
            "return cur", Long.class);

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            InventoryDeductEvent event = objectMapper.readValue(body, InventoryDeductEvent.class);

            log.info("[库存L2] 收到消息: action={}, orderId={}, skuId={}, qty={}",
                    event.getAction(), event.getOrderId(), event.getSkuId(), event.getQuantity());

            // C-008 F4：版本门"完成后推进"——只读检查旧事件（跨 action 乱序防护；不前置占位）
            if (isStaleEvent(event)) {
                log.debug("[库存L2] 跳过旧版本事件: orderId={}, skuId={}, action={}, eventTime={}",
                        event.getOrderId(), event.getSkuId(), event.getAction(), event.getEventTime());
                return;
            }

            // C-008 F4：事务内幂等——INSERT IGNORE 与库存变更同事务（补发/重投/崩溃重投不双扣不丢）
            // 幂等键含 eventTime：分次退款（同 order+sku 不同事件）各自应用；重发（同 eventTime）去重
            boolean applied = inboxService.applyOnce(event.getOrderId(), event.getSkuId(), event.getAction(),
                    event.getEventTime(), () -> dispatch(event));
            if (!applied) {
                log.warn("[库存L2] 逻辑事件幂等拦截(已应用过): orderId={}, skuId={}, action={}",
                        event.getOrderId(), event.getSkuId(), event.getAction());
            }
            // 完成后单调推进版本（应用与否都推进：收敛乱序状态）
            advanceEventVersion(event);
        } catch (Exception e) {
            log.error("[库存L2] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("库存消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /** 按 action 分派（在事务内执行——inbox 幂等与库存变更同事务提交） */
    private void dispatch(InventoryDeductEvent event) {
        switch (event.getAction()) {
            case "PRE_DEDUCT" -> doHandlePreDeduct(event);
            case "CONFIRM" -> doHandleConfirm(event);
            case "RELEASE" -> doHandleRelease(event);
            case "REFUND_RESTORE" -> doHandleRefundRestore(event);
            default -> log.warn("[库存L2] 未知操作类型: {}", event.getAction());
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
    private void doHandlePreDeduct(InventoryDeductEvent event) {
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            int affected = inventoryMapper.deductStock(event.getSkuId(), event.getQuantity());
            if (affected > 0) {
                log.info("[库存L2] 预扣减MySQL成功: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 1);
                businessMetrics.recordInventoryAction("l2_prededuct_ok");
                return;
            }
            // 乐观锁竞争失败，退避后重试
            if (i < maxRetry - 1) {
                try {
                    Thread.sleep(com.myxhs.common.mq.RetryBackoffUtils.jitter(50L * (i + 1), 0.2)); // 50/100ms +抖动
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("[库存L2] 预扣减MySQL重试: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 2);
            }
        }
        // 重试耗尽仍失败：发失败事件给订单侧收口（未支付关单/已支付人工），L3 对账继续兜底
        log.warn("[库存L2] 预扣减MySQL失败({}次重试后仍库存不足): skuId={}, qty={}（已发失败事件，等待L3对账修复）",
                maxRetry, event.getSkuId(), event.getQuantity());
        businessMetrics.recordInventoryFailure("PREDUCT_FAILED");
        publishFailureEvent(event, "PREDUCT_FAILED");
    }

    /**
     * 确认扣减：locked_stock 减少（库存正式扣除）
     */
    private void doHandleConfirm(InventoryDeductEvent event) {
        int affected = inventoryMapper.confirmDeduct(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 确认扣减MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
            businessMetrics.recordInventoryAction("l2_confirm_ok");
        } else {
            log.warn("[库存L2] 确认扣减MySQL失败: skuId={}, qty={}（已发失败事件，等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
            businessMetrics.recordInventoryFailure("CONFIRM_FAILED");
            publishFailureEvent(event, "CONFIRM_FAILED");
        }
    }

    /**
     * 释放库存：locked_stock 回退到 available_stock
     */
    private void doHandleRelease(InventoryDeductEvent event) {
        int affected = inventoryMapper.releaseStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 释放库存MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
            businessMetrics.recordInventoryAction("l2_release_ok");
        } else {
            log.warn("[库存L2] 释放库存MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }

    /** T-071：退款回补（MySQL available_stock +qty——独立语义，非 locked→available） */
    private void doHandleRefundRestore(InventoryDeductEvent event) {
        int affected = inventoryMapper.refundRestoreStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 退款回补MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
            businessMetrics.recordInventoryAction("l2_refund_ok");
        } else {
            log.warn("[库存L2] 退款回补MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }

    /** 版本 key（按 orderId+skuId 统一，跨 action 防乱序） */
    private String eventVersionKey(InventoryDeductEvent event) {
        return EVENT_VERSION_PREFIX + event.getOrderId() + ":" + event.getSkuId();
    }

    /**
     * C-008：只读检查旧事件（version >= eventTime → 跳过）。
     * <p>
     * 语义：同一 (orderId, skuId) 只允许 eventTime 严格递增的事件应用；
     * 旧实现"先占位再执行"在崩溃时会永久丢事件（重投被自己的占位拦死），
     * 改为"完成后推进"——崩溃后重投版本未推进 → 可重新处理（inbox 幂等保证不双扣）。
     * </p>
     */
    private boolean isStaleEvent(InventoryDeductEvent event) {
        if (event.getEventTime() == null) {
            return false; // 无版本号不拦截
        }
        try {
            String v = stringRedisTemplate.opsForValue().get(eventVersionKey(event));
            if (v == null) {
                return false;
            }
            return Long.parseLong(v) >= event.getEventTime();
        } catch (NumberFormatException e) {
            return false; // 脏数据不拦截
        }
    }

    /** C-008：完成后单调推进版本（只增不减） */
    private void advanceEventVersion(InventoryDeductEvent event) {
        if (event.getEventTime() == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(versionAdvanceScript,
                    java.util.List.of(eventVersionKey(event)),
                    String.valueOf(event.getEventTime()),
                    String.valueOf(EVENT_VERSION_TTL_SECONDS));
        } catch (Exception e) {
            // 推进失败仅告警：inbox 幂等不受影响（版本门退化为下一轮重投再收敛）
            log.warn("[库存L2] 版本推进失败(幂等不受影响): orderId={}, skuId={}",
                    event.getOrderId(), event.getSkuId(), e);
        }
    }

    /**
     * 发布"库存失败"事件（订单侧收口：未支付自动关单 / 已支付人工介入）。
     * <p>
     * 尽力而为：发送失败只记日志与指标，不影响主消费（订单侧 30 分钟超时关单 + L3 对账仍在兜底）。
     * 仅对 PRE_DEDUCT/CONFIRM 两类资金相关失败发布；RELEASE/REFUND_RESTORE 失败只影响
     * MySQL 镜像（对账可修），不触发订单侧动作。
     * </p>
     */
    private void publishFailureEvent(InventoryDeductEvent source, String reason) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "orderId", source.getOrderId(),
                    "skuId", source.getSkuId(),
                    "quantity", source.getQuantity(),
                    "action", source.getAction(),
                    "reason", reason,
                    "failedAt", System.currentTimeMillis()));
            org.apache.rocketmq.client.producer.SendResult result = rocketMQTemplate.syncSend(
                    INVENTORY_FAILED_TOPIC,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000);
            if (result.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.error("[库存L2] 失败事件发送状态异常: orderId={}, reason={}, status={}",
                        source.getOrderId(), reason, result.getSendStatus());
                businessMetrics.recordInventoryFailure(reason + "_PUBLISH_NOT_OK");
            }
        } catch (Exception e) {
            log.error("[库存L2] 失败事件发送异常: orderId={}, reason={}", source.getOrderId(), reason, e);
            businessMetrics.recordInventoryFailure(reason + "_PUBLISH_EXCEPTION");
        }
    }
}
