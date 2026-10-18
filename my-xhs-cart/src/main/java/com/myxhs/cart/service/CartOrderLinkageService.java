package com.myxhs.cart.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 购物车订单联动服务（C-006：下单清车 + 取消回补）
 * <p>
 * 事件订阅式联动（对齐 04B"订阅方异步联动"哲学）：
 * <ul>
 *   <li>清车：ORDER_TRANSACTION_TOPIC（创建）→ 仅 source=CART 时批量移除订单 sku</li>
 *   <li>回补：ORDER_TERMINATION_TOPIC（终结：取消/超时/库存失败/券失败/支付失败）→ merge 回补（max + 默认勾选）</li>
 * </ul>
 * </p>
 * <p>
 * <b>定序握手协议</b>（跨 topic 乱序防护，三标记 cleared / restore-pending / restored）：
 * <pre>
 *  restore：① SETNX restore-pending → ② 查 cleared（有 → merge + SETNX restored + 删 pending）
 *  clear：  ① 批量移除 → ② SET cleared → ③ 查 pending（有 → merge + SETNX restored + 删 pending）
 * </pre>
 * 任意到达序恰好一方 merge；直接购买单置 pending 后自然过期（零副作用）；
 * clear 永久失败时商品从未被清（自愈）。双向去重：重复投递不会误删新加购 / 不会抬回用户调小的数量。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartOrderLinkageService {

    static final String MARKER_PREFIX = "myxhs:cart:linkage:";
    /** cleared/restored：覆盖订单全周期 + Outbox 重投窗口 */
    static final Duration CLEARED_TTL = Duration.ofDays(7);
    /** restore-pending：清车事件应秒级到达，1h 充裕 */
    static final Duration PENDING_TTL = Duration.ofHours(1);
    static final Duration RESTORED_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;
    private final CartService cartService;
    private final MeterRegistry meterRegistry;

    /** 联动商品项（skuId + quantity） */
    public record LinkageItem(Long skuId, Integer quantity) {
    }

    /**
     * 下单清车（ORDER_TRANSACTION_TOPIC 消费者调用）。
     *
     * @param source 订单来源（CART 才清；DIRECT/缺省不碰购物车）
     */
    public void handleOrderCreated(String orderNo, Long userId, String source, List<LinkageItem> items) {
        if (!"CART".equalsIgnoreCase(source)) {
            log.debug("[购物车联动] 非购物车来源，跳过清车: orderNo={}, source={}", orderNo, source);
            count("clear", "skip");
            return;
        }
        if (orderNo == null || userId == null || items == null || items.isEmpty()) {
            log.warn("[购物车联动] 清车参数不完整，跳过: orderNo={}", orderNo);
            count("clear", "skip");
            return;
        }

        String clearedKey = markerKey("cleared", orderNo);
        // 去重（重复投递/Outbox 重发）：已清过则跳过——防误删用户清车后的新加购
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(clearedKey))) {
            // 深审查修复（F1·部分失败重入）：竞态路径的握手回补 merge 失败后，本消费者重试会走到这里——
            // 不能因 cleared 去重而漏掉"未完成的握手回补"（否则商品被清后永不回补、终结消息已 ack 无人再触发）
            String pendingKey = markerKey("restore-pending", orderNo);
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(pendingKey))
                    && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(markerKey("restored", orderNo)))) {
                log.warn("[购物车联动] 清车重入：握手回补未完成，补做回补: orderNo={}, userId={}", orderNo, userId);
                restoreItems(orderNo, userId, items, pendingKey);
            } else {
                log.debug("[购物车联动] 清车已处理（重复投递），跳过: orderNo={}", orderNo);
                count("clear", "skip");
            }
            return;
        }

        List<Long> skuIds = items.stream().map(LinkageItem::skuId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        int removed = cartService.removeBySkus(userId, skuIds);
        stringRedisTemplate.opsForValue().set(clearedKey, "1", CLEARED_TTL);

        // 定序协议③：查 pending——竞态（回补先到）则立即回补（恰好一方 merge）
        String pendingKey = markerKey("restore-pending", orderNo);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(pendingKey))) {
            log.warn("[购物车联动] 竞态命中（回补先于清车到达），清车后立即回补: orderNo={}, userId={}", orderNo, userId);
            countHandshake();
            restoreItems(orderNo, userId, items, pendingKey);
        } else {
            log.info("[购物车联动] 下单清车完成: orderNo={}, userId={}, 移除={}", orderNo, userId, removed);
            count("clear", "ok");
        }
    }

    /**
     * 取消回补（ORDER_TERMINATION_TOPIC 消费者调用）。
     */
    public void handleOrderTerminated(String orderNo, Long userId, List<LinkageItem> items) {
        if (orderNo == null || userId == null) {
            log.warn("[购物车联动] 回补参数不完整，跳过: orderNo={}", orderNo);
            count("restore", "skip");
            return;
        }
        if (items == null || items.isEmpty()) {
            // 兼容 eventVersion=1 旧事件（无 items）——该时期无清车，回补无意义
            log.warn("[购物车联动] 回补跳过（无 items——旧版本事件）: orderNo={}", orderNo);
            count("restore", "skip");
            return;
        }

        String restoredKey = markerKey("restored", orderNo);
        // 去重（重复投递）：已回补则跳过——防把用户调小的数量抬回去
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(restoredKey))) {
            log.debug("[购物车联动] 回补已处理（重复投递），跳过: orderNo={}", orderNo);
            count("restore", "skip");
            return;
        }

        // 定序协议①：先置 pending（clear 侧后续才可能看到）
        String pendingKey = markerKey("restore-pending", orderNo);
        stringRedisTemplate.opsForValue().set(pendingKey, "1", PENDING_TTL);

        // 定序协议②：再查 cleared
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(markerKey("cleared", orderNo)))) {
            log.info("[购物车联动] 回补（清车已处理）: orderNo={}, userId={}", orderNo, userId);
            restoreItems(orderNo, userId, items, pendingKey);
        } else {
            // 商品仍在车中（清车未到或未发生——直接购买单 / clear 失败自愈）；pending 待 clear 侧消费或自然过期
            // E1（深审查）：直接购买单/竞态前半段每次终结都会走到这里——降为 DEBUG（竞态由 clear 侧 WARN 覆盖）
            log.debug("[购物车联动] 回补挂起（清车未到），置 pending 待握手: orderNo={}", orderNo);
            count("restore", "skip");
        }
    }

    /** 逐项 merge 回补（max 幂等，失败抛错重试收敛）；整批成功后 SETNX restored + 删 pending */
    private void restoreItems(String orderNo, Long userId, List<LinkageItem> items, String pendingKey) {
        int restored = 0;
        for (LinkageItem item : items) {
            if (item.skuId() == null) {
                continue;
            }
            Integer quantity = item.quantity() != null && item.quantity() > 0 ? item.quantity() : 1;
            try {
                if (cartService.mergeItem(userId, item.skuId(), quantity)) {
                    restored++;
                }
            } catch (Exception e) {
                // 失败抛出：MQ 重试（restored 未置位，重入幂等——merge max 收敛）
                throw new IllegalStateException("回补失败(重试收敛): orderNo=" + orderNo + ", skuId=" + item.skuId(), e);
            }
        }
        stringRedisTemplate.opsForValue().setIfAbsent(markerKey("restored", orderNo), "1", RESTORED_TTL);
        stringRedisTemplate.delete(pendingKey);
        log.info("[购物车联动] 回补完成: orderNo={}, userId={}, 恢复={}/{}", orderNo, userId, restored, items.size());
        count("restore", "ok");
    }

    private String markerKey(String type, String orderNo) {
        return MARKER_PREFIX + type + ":" + orderNo;
    }

    private void count(String op, String result) {
        Counter.builder("myxhs_cart_order_linkage_total")
                .tag("op", op).tag("result", result)
                .register(meterRegistry).increment();
    }

    private void countHandshake() {
        Counter.builder("myxhs_cart_linkage_handshake_total")
                .tag("path", "restore-arrived-first")
                .register(meterRegistry).increment();
    }
}
