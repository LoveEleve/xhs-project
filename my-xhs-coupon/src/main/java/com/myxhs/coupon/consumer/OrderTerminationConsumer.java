package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.dto.request.ReturnCouponRequest;
import com.myxhs.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单终结事件消费者 — 退还优惠券（04B）
 * <p>
 * 消费 ORDER_TERMINATION_TOPIC（Outbox 投递：即时发送 + LocalMessageRetryJob 兜底补发）。
 * 订单终结（用户取消/超时/库存失败/券失败）后，把该订单核销的券恢复为"未使用"。
 * </p>
 * <p>
 * 幂等保证（三重）：
 * 1. 消息 KEYS（=orderNo）去重：跨重投稳定（Outbox 补发换新 msgId，msgId 去重会失效）
 * 2. returnCoupon 乐观锁（WHERE status=1 AND used_order_id=orderId）：重复调用 0 行更新，幂等
 * 3. COUPON_NOT_FOUND（券不存在/归属不符）视为已收敛，直接 ACK 不重试
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_TERMINATION_TOPIC",
        consumerGroup = "coupon-order-termination-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class OrderTerminationConsumer implements RocketMQListener<MessageExt> {

    private final CouponService couponService;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "coupon:order-termination:consumed";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(body);

            Long userId = resolveLong(payload.get("userId"), "userId");
            Long orderId = resolveLong(payload.get("orderId"), "orderId");
            JsonNode couponIdNode = payload.get("couponId");

            String dedupKey = resolveDedupKey(msg);
            // C-017 review：完成态标记（处理后置位）——避免"前置标记 + 崩溃"导致退券永久丢失
            if (idempotentHelper.isProcessed(BIZ_TYPE, dedupKey)) {
                return;
            }

            if (couponIdNode == null || couponIdNode.isNull()) {
                log.info("[优惠券-终结消费] 订单未使用优惠券, 跳过: orderId={}", orderId);
                idempotentHelper.markProcessed(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS);
                return;
            }
            Long couponId = resolveLong(couponIdNode, "couponId");

            ReturnCouponRequest request = new ReturnCouponRequest();
            request.setUserCouponId(couponId);
            request.setOrderId(orderId);
            try {
                couponService.returnCoupon(userId, request);
                idempotentHelper.markProcessed(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS);
                log.info("[优惠券-终结消费] 退券完成: orderId={}, userCouponId={}", orderId, couponId);
            } catch (BizException e) {
                if (ResultCode.COUPON_NOT_FOUND.getCode() == e.getCode()) {
                    // 券不存在/不属于该用户 → 数据已收敛，不重试（对齐 returnCoupon 幂等口径）
                    log.warn("[优惠券-终结消费] 退券跳过(券不存在/归属不符): orderId={}, userCouponId={}",
                            orderId, couponId);
                    idempotentHelper.markProcessed(BIZ_TYPE, dedupKey, IDEMPOTENT_TTL_SECONDS);
                    return;
                }
                throw e;
            }
        } catch (RuntimeException e) {
            // C-017 review：失败不置完成标记，MQ 重投可重新处理（returnCoupon 自身幂等）
            throw e;
        } catch (Exception e) {
            log.error("[优惠券-终结消费] 消费失败: msgId={}", msg.getMsgId(), e);
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

    /**
     * 兼容数字/字符串形式的 Long 字段解析。
     * <p>JacksonConfig 全局把 Long 序列化为 String（防 JS 精度丢失），
     * MQ body 中的 id 字段可能是 "10001"（字符串）而非 10001（数字）。</p>
     */
    private Long resolveLong(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException("订单终结消息缺少关键字段(" + fieldName + ")");
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("订单终结消息字段非法(" + fieldName + ")=" + node.asText());
            }
        }
        throw new IllegalArgumentException("订单终结消息字段非法(" + fieldName + ")");
    }
}
