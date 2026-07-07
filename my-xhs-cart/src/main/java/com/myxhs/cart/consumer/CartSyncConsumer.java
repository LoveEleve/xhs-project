package com.myxhs.cart.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.dto.event.CartSyncEvent;
import com.myxhs.cart.entity.CartItem;
import com.myxhs.cart.mapper.CartItemMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 购物车同步消费者
 * <p>
 * 消费 CART_TOPIC 消息，将购物车变更异步持久化到 MySQL。
 * MySQL 作为兜底存储，Redis 故障时可从 MySQL 恢复购物车数据。
 * </p>
 * <p>
 * 幂等保证：使用 UPSERT（INSERT ON DUPLICATE KEY UPDATE）语义，
 * 重复消费不会产生脏数据。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "CART_TOPIC",
        consumerGroup = "cart-sync-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 3
)
public class CartSyncConsumer implements RocketMQListener<MessageExt> {

    private final CartItemMapper cartItemMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        // 恢复 TraceId
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody());
            CartSyncEvent event = objectMapper.readValue(body, CartSyncEvent.class);

            log.info("[购物车同步] 收到消息: action={}, userId={}, skuId={}",
                    event.getAction(), event.getUserId(), event.getSkuId());

            switch (event.getAction()) {
                case "ADD", "UPDATE" -> upsertCartItem(event);
                case "DELETE" -> deleteCartItem(event);
                case "CHECK" -> updateCheckedStatus(event);
                default -> log.warn("[购物车同步] 未知操作类型: {}", event.getAction());
            }
        } catch (Exception e) {
            log.error("[购物车同步] 消费失败: msgId={}", msg.getMsgId(), e);
            // 抛出异常触发 RocketMQ 重试
            throw new RuntimeException("购物车同步消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 新增/更新购物车项（真正的 UPSERT 语义）
     * <p>
     * 幂等保证：
     * 利用 MySQL 唯一索引 uk_user_sku(user_id, sku_id) + try-catch 实现真正的 UPSERT。
     * 先尝试 INSERT，如果唯一索引冲突（DuplicateKeyException）则转为 UPDATE。
     * 这比 select + insert/update 两步操作更安全——后者在并发消费时，
     * 两个线程可能同时 select 为空，然后都执行 insert，导致唯一索引冲突异常。
     * </p>
     */
    private void upsertCartItem(CartSyncEvent event) {
        try {
            // 先尝试 INSERT
            CartItem item = new CartItem();
            item.setId(idGeneratorUtil.nextId());
            item.setUserId(event.getUserId());
            item.setSkuId(event.getSkuId());
            item.setQuantity(event.getQuantity());
            item.setChecked(event.getChecked() != null ? event.getChecked() : 1);
            cartItemMapper.insert(item);
            log.info("[购物车同步] 新增成功: userId={}, skuId={}, quantity={}",
                    event.getUserId(), event.getSkuId(), event.getQuantity());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突 → 转为 UPDATE（利用 uk_user_sku 保证幂等）
            CartItem existing = cartItemMapper.selectOne(
                    new LambdaQueryWrapper<CartItem>()
                            .eq(CartItem::getUserId, event.getUserId())
                            .eq(CartItem::getSkuId, event.getSkuId())
            );
            if (existing != null) {
                existing.setQuantity(event.getQuantity());
                if (event.getChecked() != null) {
                    existing.setChecked(event.getChecked());
                }
                cartItemMapper.updateById(existing);
            }
            log.info("[购物车同步] 更新成功(UPSERT): userId={}, skuId={}, quantity={}",
                    event.getUserId(), event.getSkuId(), event.getQuantity());
        }
    }

    /**
     * 删除购物车项
     */
    private void deleteCartItem(CartSyncEvent event) {
        int deleted = cartItemMapper.delete(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId())
        );
        log.info("[购物车同步] 删除: userId={}, skuId={}, affected={}",
                event.getUserId(), event.getSkuId(), deleted);
    }

    /**
     * 更新选中状态
     */
    private void updateCheckedStatus(CartSyncEvent event) {
        CartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId())
        );

        if (existing != null) {
            existing.setChecked(event.getChecked());
            cartItemMapper.updateById(existing);
            log.info("[购物车同步] 勾选更新: userId={}, skuId={}, checked={}",
                    event.getUserId(), event.getSkuId(), event.getChecked());
        }
    }
}
