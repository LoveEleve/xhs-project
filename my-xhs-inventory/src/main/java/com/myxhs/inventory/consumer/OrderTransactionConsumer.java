package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.service.InventoryService;
import com.myxhs.inventory.util.PseudoOrderIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单事务消息消费者 — 下单成功后预扣库存
 * <p>
 * 消费 ORDER_TRANSACTION_TOPIC 消息（由 OrderTransactionListener Commit 后可见）。
 * 收到消息后对订单中的每个 SKU 执行预扣减。
 * </p>
 * <p>
 * 幂等保证（C-008 F4 完成态语义）：
 * 1. 消费者层面：MessageIdempotentHelper **完成态标记**（处理成功后置位，isProcessed 只读拦截重投）——
 *    处理中崩溃无标记 → 重投重新处理（旧"前置标记"会把重投拦死，未处理 SKU 永久漏扣）
 * 2. InventoryService 层面：Lua 脚本内置幂等（预扣记录已存在则返回 -1）——重投时已扣 SKU 跳过、未扣补扣
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

    private final StringRedisTemplate stringRedisTemplate;

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
            if (log.isDebugEnabled()) {
                log.debug("[库存-事务消费] 收到消息体: msgId={}, body={}", msg.getMsgId(), body);
            }
            JsonNode payload = objectMapper.readTree(body);

            JsonNode orderNoNode = payload.get("orderNo");
            JsonNode userIdNode = payload.get("userId");
            JsonNode skuItems = payload.get("skuItems");
            if (orderNoNode == null || orderNoNode.isNull() || orderNoNode.asText().isBlank()
                    || userIdNode == null || userIdNode.isNull()
                    || skuItems == null || !skuItems.isArray() || skuItems.isEmpty()) {
                log.warn("[库存-事务消费] 消息体字段缺失: msgId={}, body={}", msg.getMsgId(), body);
                throw new IllegalArgumentException("订单事务消息缺少关键字段(orderNo/userId/skuItems)");
            }
            String orderNo = orderNoNode.asText();
            // JacksonConfig 全局把 Long 序列化为 String（防 JS 精度丢失），userId 可能是字符串形式，
            // 用 resolveLong 兼容数字/字符串两种形态。
            Long userId = resolveLong(userIdNode, "userId");

            // C-008 F4 完成态标记：只读检查"已完成"（不置位）——处理中崩溃无标记，重投可重新处理；
            // 已扣 SKU 由 prededuct.lua 幂等跳过、未扣 SKU 补扣（见方法尾部置标记）
            if (idempotentHelper.isProcessed(BIZ_TYPE, msg.getMsgId())) {
                log.info("[库存-事务消费] 已完成处理(重投跳过): msgId={}", msg.getMsgId());
                return;
            }

            // 从 orderNo 派生伪 orderId（库存预扣幂等键）
            // T-067（2026-08-14 G5 执行发现）：原 fold-hash(*31+char) 与订单域 SHA-256 实现不一致 →
            // 预扣 key 与释放/确认 key 对不上 → 库存永远无法释放（泄漏）。
            // 统一收敛到 PseudoOrderIdUtil（与终结/释放侧共用同一实现，算法必须完全一致）。
            long pseudoOrderId = PseudoOrderIdUtil.derive(orderNo);

            for (JsonNode item : skuItems) {
                Long skuId = resolveLong(item.get("skuId"), "skuId");
                Integer quantity = resolveInt(item.get("quantity"), "quantity");
                if (quantity <= 0) {
                    throw new IllegalArgumentException("订单事务消息 skuItems 字段异常(quantity<=0)");
                }

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
                            // RV31：原实现静默 return（消息被 ACK、订单已提交但库存未扣且不可见）。
                            // 改为写异常集合（供对账/人工处理）+ 抛错进入重试→DLQ（死信监控可见+可审批处置）。
                            log.error("[库存-事务消费] 不可恢复坏消息，SKU不存在: orderNo={}, skuId={}, qty={}",
                                    orderNo, skuId, quantity, e);
                            try {
                                stringRedisTemplate.opsForSet().add("myxhs:inventory:anomaly:sku-missing", orderNo);
                                stringRedisTemplate.expire("myxhs:inventory:anomaly:sku-missing",
                                        java.time.Duration.ofDays(7));
                            } catch (Exception ignore) {
                                // 标记失败不影响主流程
                            }
                            throw new RuntimeException("库存预扣减失败(不可恢复): SKU不存在 skuId=" + skuId, e);
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
            // C-008 F4：完成后置标记（崩溃窗口修复——前置标记会把重投拦死，未处理 SKU 永久漏扣）
            idempotentHelper.markProcessed(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS);

        } catch (RuntimeException e) {
            // C-008 F4 完成态语义：失败不置标记（无需 removeMark）→ 重投重新处理；
            // 已处理成功的 SKU 由 prededuct.lua 幂等跳过，未处理的补扣
            throw e; // 直接抛出触发 MQ 重试
        } catch (Exception e) {
            log.error("[库存-事务消费] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("订单事务消息消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 兼容数字/字符串形式的 Long 字段解析。
     * <p>JacksonConfig 全局把 Long 序列化为 String（防前端 JS 精度丢失），
     * MQ body 中的 id 字段可能是 "10001"（字符串）而非 10001（数字）。
     * canConvertToLong() 对字符串节点返回 false，必须手动解析。</p>
     */
    private Long resolveLong(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException("订单事务消息缺少关键字段(" + fieldName + ")");
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("订单事务消息字段非法(" + fieldName + ")=" + node.asText());
            }
        }
        throw new IllegalArgumentException("订单事务消息字段非法(" + fieldName + ")");
    }

    /**
     * 兼容数字/字符串形式的 Integer 字段解析。
     */
    private Integer resolveInt(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException("订单事务消息缺少关键字段(" + fieldName + ")");
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("订单事务消息字段非法(" + fieldName + ")=" + node.asText());
            }
        }
        throw new IllegalArgumentException("订单事务消息字段非法(" + fieldName + ")");
    }
}
