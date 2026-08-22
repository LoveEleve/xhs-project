package com.myxhs.cart.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

import java.time.LocalDateTime;
import java.time.ZoneId;

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

    private static final java.util.concurrent.ConcurrentHashMap<Long, LocalDateTime> CLEAR_BARRIERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final CartItemMapper cartItemMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        // 恢复 TraceId
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            CartSyncEvent event = objectMapper.readValue(body, CartSyncEvent.class);

            log.info("[购物车同步] 收到消息: action={}, userId={}, skuId={}",
                    event.getAction(), event.getUserId(), event.getSkuId());

            // 脏消息防御：action 为 null 时 switch 会 NPE 导致无意义重试 3 次进 DLQ，直接跳过
            if (event.getAction() == null) {
                log.warn("[购物车同步] 消息 action 为 null, 跳过: msgId={}", msg.getMsgId());
                return;
            }

            switch (event.getAction()) {
                case "ADD", "UPDATE" -> upsertCartItem(event);
                case "DELETE" -> deleteCartItem(event);
                case "CHECK" -> updateCheckedStatus(event);
                case "CHECK_ALL" -> checkAllItems(event);
                case "CLEAR" -> clearCartItems(event);
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
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
        LocalDateTime clearBarrier = CLEAR_BARRIERS.get(event.getUserId());
        if (clearBarrier != null && eventTime.isBefore(clearBarrier)) {
            log.warn("[购物车同步] 跳过CLEAR屏障之前的旧写事件: action={}, userId={}, skuId={}, eventTime={}, clearBarrier={}",
                    event.getAction(), event.getUserId(), event.getSkuId(), eventTime, clearBarrier);
            return;
        }

        // C-05: 时间戳乱序保护 — 先查是否存在更新的行
        CartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId()));
        if (existing != null && existing.getUpdatedAt() != null
                && !existing.getUpdatedAt().isBefore(eventTime)) {
            log.debug("[购物车同步] 跳过旧事件: userId={}, skuId={}, eventTime={}, dbTime={}",
                    event.getUserId(), event.getSkuId(), eventTime, existing.getUpdatedAt());
            return;
        }

        try {
            CartItem item = new CartItem();
            item.setId(idGeneratorUtil.nextId());
            item.setUserId(event.getUserId());
            item.setSkuId(event.getSkuId());
            item.setQuantity(event.getQuantity());
            item.setChecked(event.getChecked() != null ? event.getChecked() : 1);
            item.setCreatedAt(eventTime);
            item.setUpdatedAt(eventTime);
            cartItemMapper.insert(item);
            log.info("[购物车同步] 新增成功: userId={}, skuId={}, quantity={}",
                    event.getUserId(), event.getSkuId(), event.getQuantity());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            CartItem existing2 = cartItemMapper.selectOne(
                    new LambdaQueryWrapper<CartItem>()
                            .eq(CartItem::getUserId, event.getUserId())
                            .eq(CartItem::getSkuId, event.getSkuId()));
            if (existing2 == null) {
                log.warn("[购物车同步] UPSERT异常: DuplicateKeyException后selectOne返回null, userId={}, skuId={}",
                        event.getUserId(), event.getSkuId());
                return;
            }
            // C-05: catch 块也检查时间戳 — 防止 TOCTOU 窗口（初始 SELECT 无行→并发写入→旧事件覆盖）
            if (existing2.getUpdatedAt() != null && !existing2.getUpdatedAt().isBefore(eventTime)) {
                log.debug("[购物车同步] 跳过旧事件(catch): userId={}, skuId={}, eventTime={}, dbTime={}",
                        event.getUserId(), event.getSkuId(), eventTime, existing2.getUpdatedAt());
                return;
            }
            existing2.setQuantity(event.getQuantity());
            if (event.getChecked() != null) {
                existing2.setChecked(event.getChecked());
            }
            // 使用 eventTime（生产者时钟）而非 now()（消费者时钟），统一时钟域保证乱序比较正确
            existing2.setUpdatedAt(eventTime);
            existing2.setCreatedAt(eventTime);  // 同步重置createdAt: 防CLEAR按createdAt误删"清空后重新加购"行
            cartItemMapper.updateById(existing2);
            log.info("[购物车同步] 更新成功(UPSERT): userId={}, skuId={}, quantity={}",
                    event.getUserId(), event.getSkuId(), event.getQuantity());
        }
    }

    /**
     * 删除购物车项（C-05: 时间戳保护，避免旧DELETE覆盖新ADD）
     */
    private void deleteCartItem(CartSyncEvent event) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
        LocalDateTime clearBarrier = CLEAR_BARRIERS.get(event.getUserId());
        if (clearBarrier != null && eventTime.isBefore(clearBarrier)) {
            log.warn("[购物车同步] 跳过CLEAR屏障之前的旧DELETE: userId={}, skuId={}, eventTime={}, clearBarrier={}",
                    event.getUserId(), event.getSkuId(), eventTime, clearBarrier);
            return;
        }

        // C-05: 仅当行未被更晚的事件覆盖时才删除
        CartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId()));
        if (existing == null) return;
        // C-05 边界语义统一为 !isBefore（dbTime >= eventTime 即跳过），与 upsert/updateCheckedStatus 一致
        if (existing.getUpdatedAt() != null && !existing.getUpdatedAt().isBefore(eventTime)) {
            log.debug("[购物车同步] 跳过旧DELETE: userId={}, skuId={}, eventTime={}, dbTime={}",
                    event.getUserId(), event.getSkuId(), eventTime, existing.getUpdatedAt());
            return;
        }

        int deleted = cartItemMapper.delete(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId()));
        log.info("[购物车同步] 删除: userId={}, skuId={}, affected={}",
                event.getUserId(), event.getSkuId(), deleted);
    }

    /**
     * 清空购物车（C-04: 时间戳保护，仅删创建时间早于事件的操作之前的行）
     */
    private void clearCartItems(CartSyncEvent event) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
        CLEAR_BARRIERS.put(event.getUserId(), eventTime);
        // 按 updatedAt 过滤: 只删"事件时间之前最后修改"的行
        // 修复: createdAt 在 UPSERT 时不重置，CLEAR 按 createdAt 会误删"清空后重新加购"的行
        int deleted = cartItemMapper.delete(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .lt(CartItem::getUpdatedAt, eventTime));
        log.info("[购物车同步] 清空: userId={}, cutoffTime={}, barrierTs={}, affected={}",
                event.getUserId(), eventTime, event.getClearBarrierTs(), deleted);
    }

    /**
     * 全选/取消全选（C-11: CHECK_ALL 事件处理）
     */
    private void checkAllItems(CartSyncEvent event) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
        LocalDateTime clearBarrier = CLEAR_BARRIERS.get(event.getUserId());
        if (clearBarrier != null && eventTime.isBefore(clearBarrier)) {
            log.warn("[购物车同步] 跳过CLEAR屏障之前的旧CHECK_ALL: userId={}, eventTime={}, clearBarrier={}",
                    event.getUserId(), eventTime, clearBarrier);
            return;
        }
        // C-05 时间戳保护：仅更新 updatedAt 早于事件时间的行，防止旧 CHECK_ALL 全量覆盖新 CHECK
        // 使用 eventTime（生产者时钟）而非 now()（消费者时钟），与其他写路径统一时钟域
        LambdaUpdateWrapper<CartItem> wrapper = new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getUserId, event.getUserId())
                .lt(CartItem::getUpdatedAt, eventTime)
                .set(CartItem::getChecked, event.getChecked() != null ? event.getChecked() : 1)
                .set(CartItem::getUpdatedAt, eventTime);
        int updated = cartItemMapper.update(null, wrapper);
        log.info("[购物车同步] 全选更新: userId={}, checked={}, affected={}",
                event.getUserId(), event.getChecked(), updated);
    }

    /**
     * 更新选中状态（带时间戳保护，对齐 upsertCartItem/deleteCartItem 的 C-05）
     * <p>
     * 仅当事件时间戳晚于 DB 更新时间时才应用，防止 MQ 乱序导致旧 CHECK 事件覆盖新值。
     * </p>
     */
    private void updateCheckedStatus(CartSyncEvent event) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
        LocalDateTime clearBarrier = CLEAR_BARRIERS.get(event.getUserId());
        if (clearBarrier != null && eventTime.isBefore(clearBarrier)) {
            log.warn("[购物车同步] 跳过CLEAR屏障之前的旧CHECK: userId={}, skuId={}, eventTime={}, clearBarrier={}",
                    event.getUserId(), event.getSkuId(), eventTime, clearBarrier);
            return;
        }

        // C-05: 时间戳乱序保护 — 仅当事件比 DB 更新时才应用
        CartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, event.getUserId())
                        .eq(CartItem::getSkuId, event.getSkuId())
        );

        if (existing == null) {
            // 乱序 CHECK 先于 ADD/UPDATE 到达时，不凭空创建购物车项；等待后续主事件或对账恢复。
            log.warn("[购物车同步] CHECK事件对应条目不存在, 跳过避免伪造购物车项: userId={}, skuId={}",
                    event.getUserId(), event.getSkuId());
            return;
        }
        if (existing.getUpdatedAt() != null && !existing.getUpdatedAt().isBefore(eventTime)) {
            log.debug("[购物车同步] 跳过旧CHECK事件: userId={}, skuId={}, eventTime={}, dbTime={}",
                    event.getUserId(), event.getSkuId(), eventTime, existing.getUpdatedAt());
            return;
        }

        existing.setChecked(event.getChecked());
        // 使用 eventTime（生产者时钟）而非 now()，统一时钟域
        existing.setUpdatedAt(eventTime);
        cartItemMapper.updateById(existing);
        log.info("[购物车同步] 勾选更新: userId={}, skuId={}, checked={}",
                event.getUserId(), event.getSkuId(), event.getChecked());
    }
}
