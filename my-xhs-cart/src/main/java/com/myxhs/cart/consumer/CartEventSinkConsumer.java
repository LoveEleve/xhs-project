package com.myxhs.cart.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.dto.event.CartSyncEvent;
import com.myxhs.cart.entity.CartEvent;
import com.myxhs.cart.mapper.CartEventMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 购物车事件流水消费者（append-only，可观测性）
 * <p>
 * 独立 consumer group（cart-event-sink-group）订阅 CART_TOPIC，
 * 追加落库 t_cart_event，与 cart-sync-consumer-group（同步 t_cart_item）互不影响。
 * 用于还原历史加购/改量/勾选/删除时段（漏斗分析）。
 * </p>
 * <p>
 * 幂等双重：① MessageIdempotentHelper（msgId, 24h）② t_cart_event.uk_msg_id 唯一索引兜底（V1__cart_event_uk_msg_id.sql）。
 * 字段约定：购物车级动作（CLEAR/CHECK_ALL）无具体 SKU，sku_id 落 0 哨兵；其余动作缺 skuId 视为脏消息跳过。
 * 失败容忍：本消费者失败不影响 CART_TOPIC 其他消费者与购物车主链路。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "CART_TOPIC",
        consumerGroup = "cart-event-sink-group",
        selectorExpression = "*",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 3
)
public class CartEventSinkConsumer implements RocketMQListener<MessageExt> {

    private final CartEventMapper eventMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "cart:event";
    /** 购物车级动作：无具体 SKU，落库用 0 哨兵 */
    private static final java.util.Set<String> NO_SKU_ACTIONS = java.util.Set.of("CLEAR", "CHECK_ALL");
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        String msgId = msg.getMsgId();
        try {
            // 幂等检查
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msgId, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            CartSyncEvent event = objectMapper.readValue(body, CartSyncEvent.class);

            // 脏消息防御：action/userId/timestamp 为 null 时跳过（与主消费者保持一致的防御语义）
            if (event.getAction() == null || event.getUserId() == null || event.getTimestamp() == null) {
                log.warn("[购物车事件] 脏消息跳过: msgId={}", msgId);
                idempotentHelper.removeMark(BIZ_TYPE, msgId);
                return;
            }

            // 购物车级动作（CLEAR/CHECK_ALL）无具体 SKU：用 0 哨兵落库（表 sku_id NOT NULL；RV11）
            Long skuId = event.getSkuId();
            if (NO_SKU_ACTIONS.contains(event.getAction())) {
                skuId = 0L;
            } else if (skuId == null) {
                log.warn("[购物车事件] 脏消息跳过（非CLEAR缺少skuId）: msgId={}, action={}", msgId, event.getAction());
                idempotentHelper.removeMark(BIZ_TYPE, msgId);
                return;
            }

            CartEvent ev = new CartEvent();
            ev.setId(idGeneratorUtil.nextId());
            ev.setUserId(event.getUserId());
            ev.setSkuId(skuId);
            ev.setAction(event.getAction());
            ev.setQuantity(event.getQuantity() != null ? event.getQuantity() : 0);
            ev.setChecked(event.getChecked() != null ? event.getChecked() : 0);
            ev.setEventTime(LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault()));
            ev.setMsgId(msgId);

            try {
                eventMapper.insert(ev);
            } catch (DuplicateKeyException e) {
                // uk_msg_id 兜底幂等：消息已落库
                log.warn("[购物车事件] 重复消息(uk_msg_id兜底): msgId={}", msgId);
                return;
            }

            log.info("[购物车事件] 落库: action={}, userId={}, skuId={}, quantity={}",
                    ev.getAction(), ev.getUserId(), ev.getSkuId(), ev.getQuantity());
        } catch (Exception e) {
            // 失败清除幂等标记，允许 MQ 重试
            try {
                idempotentHelper.removeMark(BIZ_TYPE, msgId);
            } catch (Exception markEx) {
                log.error("[购物车事件] 清除幂等标记失败: msgId={}", msgId, markEx);
            }
            log.error("[购物车事件] 消费失败: msgId={}", msgId, e);
            throw new RuntimeException("购物车事件消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
