package com.myxhs.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.event.InventoryDeductEvent;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.InventoryInitRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.dto.request.StockItem;
import com.myxhs.inventory.dto.response.StockVO;
import com.myxhs.inventory.entity.Inventory;
import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;

/**
 * 库存服务（C-008 键模型收敛：单库存键 + per-(sku,order) 记录）
 * <p>
 * 核心设计：Redis 实时主账（L1）+ MySQL 持久备份账（L2）+ 对账收敛（L3）
 * </p>
 * <p>
 * 三级扣减保证：
 * L1: Redis 原子预扣（Lua 单脚本：墓碑检查 + 幂等 + 扣减 + 记账 + 索引）
 * L2: MQ 异步落 MySQL（事务内幂等：INSERT IGNORE + 变更同事务）
 * L3: 定时对账修复（Redis ↔ MySQL 最终一致，CAS 条件更新）
 * </p>
 * <p>
 * Redis Key 设计（全部使用 {skuId} hash tag——每个 Lua 脚本单 slot，Cluster 兼容）：
 * - 单库存键：inventory:{skuId}:stock（String，值=可用库存）
 * - 预扣记录：inventory:{skuId}:prededuct:{orderId}（String，值=预扣数量；物理 TTL=到期+缓冲）
 * - 终结墓碑：inventory:{skuId}:terminated:{orderId}（String，04B：拦截迟到/重投预扣）
 * - 过期索引：inventory:{skuId}:prededuct:index（ZSet，member=orderId，score=到期时间戳ms）
 * - 退款累计：inventory:{skuId}:refund:{orderId}（String，累计已受理回补量；与 stock 同脚本原子）
 * - 确认标记：inventory:confirmed:{orderId}（String，confirm 记录缺失时写入；超时任务改判补确认）
 * </p>
 * <p>
 * 【C-008 记录不做】"真分片形态"（桶粒度 tag + 跨节点 + 放弃跨 key 原子）为演进方向：
 * 需分片部署 + 对账兜底，当前规模不投入（原分桶机制因"total 热 key 抵消 + 同 slot 无并发收益"移除）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final InventoryMapper inventoryMapper;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> preDeductScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> refundRestoreScript;
    private final DefaultRedisScript<Long> confirmScript;
    private final DefaultRedisScript<Long> markTerminatedScript;
    private final BusinessMetrics businessMetrics;
    private final org.redisson.api.RedissonClient redissonClient;

    /** 补偿类型：1-预扣回滚 2-退款回补 */
    private static final int COMPENSATION_TYPE_REFUND_RESTORE = 2;

    /** 预扣记录物理 TTL 相对到期时间的缓冲（秒）：保证超时任务到期后仍可回退 */
    private static final int PREDEDUCT_RECORD_TTL_BUFFER_SECONDS = 600;

    @Value("${inventory.prededuct.expire-seconds:1800}")
    private int preDeductExpireSeconds;

    /**
     * 04B 终结墓碑 TTL（秒）：订单终结后写入，拦截迟到/重投的预扣消息。
     * 须 ≥ 预扣消息可能迟到的最晚时间（MQ 重试 5 次退避 + DLQ 重投 + 本地消息补发窗口）。
     */
    @Value("${inventory.terminated.tombstone-ttl-seconds:86400}")
    private long terminatedTombstoneTtlSeconds;

    /**
     * 04B 收尾：确认标记 TTL（秒）——confirm 记录缺失时写入（支付确认早于预扣落的场景）。
     * 须 ≥ 预扣消息可能迟到的最晚时间 + 记录释放时点（约 30min），保证迟到记录落盘后仍受保护。
     */
    @Value("${inventory.confirmed.marker-ttl-seconds:86400}")
    private long confirmedMarkerTtlSeconds;

    private final com.myxhs.inventory.feign.ProductFeignClient productFeignClient;

    /**
     * Redis Key 模板（C-008：全部 {skuId} tag——同脚本单 slot）
     */
    private static final String STOCK_KEY_TPL = "inventory:{%d}:stock";
    private static final String PREDEDUCT_KEY_TPL = "inventory:{%d}:prededuct:%d";
    private static final String TERMINATED_KEY_TPL = "inventory:{%d}:terminated:%d";
    private static final String PREDEDUCT_INDEX_KEY_TPL = "inventory:{%d}:prededuct:index";
    private static final String REFUND_KEY_TPL = "inventory:{%d}:refund:%d";
    /** 04B 确认标记 key（orderId）：confirm 记录缺失时写入；超时任务凭它改判"补确认"而非释放 */
    private static final String CONFIRMED_MARKER_KEY_PREFIX = "inventory:confirmed:";

    private static String stockKey(Long skuId) { return String.format(STOCK_KEY_TPL, skuId); }
    private static String predeductKey(Long skuId, Long orderId) { return String.format(PREDEDUCT_KEY_TPL, skuId, orderId); }
    private static String terminatedTombstoneKey(Long skuId, Long orderId) { return String.format(TERMINATED_KEY_TPL, skuId, orderId); }
    private static String predeductIndexKey(Long skuId) { return String.format(PREDEDUCT_INDEX_KEY_TPL, skuId); }
    private static String refundKey(Long skuId, Long orderId) { return String.format(REFUND_KEY_TPL, skuId, orderId); }
    private static String confirmedMarkerKey(Long orderId) { return CONFIRMED_MARKER_KEY_PREFIX + orderId; }

    /** MQ Topic */
    private static final String INVENTORY_TOPIC = "INVENTORY_TOPIC";

    // ==================== 库存初始化 ====================

    /**
     * 库存初始化（DB → Redis 单键）
     * <p>
     * 幂等性：库存键已存在即视为已初始化（抛异常）；初始化锁防并发。
     * </p>
     */
    public void initStock(InventoryInitRequest request) {
        Long skuId = request.getSkuId();
        int totalStock = request.getTotalStock();

        String stockKey = stockKey(skuId);
        String initLockKey = "inventory:init:lock:" + skuId;
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(initLockKey, "1", java.time.Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException(ResultCode.PARAM_INVALID, "库存初始化中，请稍后重试");
        }
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                throw new BizException(ResultCode.PARAM_INVALID, "库存已初始化，如需重新初始化请先清除");
            }

            // 1. 确保 MySQL 中有库存记录
            Inventory inventory = inventoryMapper.selectOne(
                    new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
            if (inventory == null) {
                inventory = new Inventory();
                inventory.setSkuId(skuId);
                inventory.setAvailableStock(totalStock);
                inventory.setLockedStock(0);
                inventoryMapper.insert(inventory);
                log.info("[库存] 自动创建MySQL记录: skuId={}, stock={}", skuId, totalStock);
            } else {
                inventory.setAvailableStock(totalStock);
                inventory.setLockedStock(0);
                inventoryMapper.updateById(inventory);
            }

            // 2. 写单库存键
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(totalStock));

            log.info("[库存] 初始化完成: skuId={}, total={}", skuId, totalStock);
        } finally {
            stringRedisTemplate.delete(initLockKey);
        }
    }

    // ==================== 预扣减 ====================

    /**
     * 预扣减（Lua 脚本原子操作）
     * <p>
     * 这是库存扣减的 L1 层——Redis 预扣。
     * Lua 单脚本原子完成：墓碑检查 → 幂等检查 → 库存检查 → 扣减 → 记账 → 过期索引。
     * </p>
     */
    public void preDeduct(PreDeductRequest request) {
        Long skuId = request.getSkuId();
        Long orderId = request.getOrderId();
        long startTime = System.currentTimeMillis();

        // T-079（2026-08-14）：MySQL 幂等占位——insert 探测走 master（原子，无主从延迟）。
        // 0=已存在（此前已预扣成功或幂等表残留）→ 幂等返回；1=首次 → 继续。
        // 解决 Redis 预扣记录丢失后（故障/误删），同 orderId 不同 msgId 消息重投重复扣减。
        int idemInserted = inventoryMapper.insertPredeductIdem(orderId, skuId);
        if (idemInserted == 0) {
            log.warn("[库存] 预扣幂等(MySQL兜底): orderId={}, skuId={}", orderId, skuId);
            return;
        }
        boolean idemOk = false;
        try {
            idemOk = doPreDeduct(request, skuId, orderId, startTime);
        } finally {
            // 预扣未成功（库存不足/未初始化/异常）→ 删除占位允许重试；成功/幂等保留
            if (!idemOk) {
                try {
                    inventoryMapper.deletePredeductIdem(orderId, skuId);
                } catch (Exception e) {
                    log.warn("[库存] 幂等占位删除失败(下次预扣将被幂等拦截,人工处理): orderId={}", orderId, e);
                }
            }
        }
    }

    private boolean doPreDeduct(PreDeductRequest request, Long skuId, Long orderId, long startTime) {
        // T-066 自愈：库存键缺失（canal INSERT 缓存失效/Redis 故障）→ 从 MySQL 重建
        // （修复前 canal 删 key 无回填 → preDeduct fail-closed → 下单已 COMMIT 但库存不扣）
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey(skuId)))) {
            if (!rebuildStockFromDb(skuId)) {
                if (!skuExists(skuId)) {
                    throw new BizException(ResultCode.PARAM_INVALID, "SKU不存在");
                }
                throw new BizException(ResultCode.SKU_STOCK_NOT_INITIALIZED, "库存未初始化");
            }
            log.info("[库存] 库存键缺失自愈重建: skuId={}", skuId);
        }

        // Lua 原子预扣：全部 KEYS 同 {skuId} tag（单 slot）
        // KEYS[1]=终结墓碑 KEYS[2]=库存键 KEYS[3]=预扣记录 KEYS[4]=过期索引
        Long result = stringRedisTemplate.execute(
                preDeductScript,
                List.of(
                        terminatedTombstoneKey(skuId, orderId),
                        stockKey(skuId),
                        predeductKey(skuId, orderId),
                        predeductIndexKey(skuId)),
                String.valueOf(orderId),
                String.valueOf(request.getQuantity()),
                String.valueOf(preDeductExpireSeconds),
                // 记录物理 TTL = 到期 + 缓冲：让超时任务在"到期后"仍能读到记录做回退（不再需要提前释放）
                String.valueOf(preDeductExpireSeconds + PREDEDUCT_RECORD_TTL_BUFFER_SECONDS)
        );

        if (result == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "库存操作失败");
        }

        switch (result.intValue()) {
            case 1 -> {
                log.info("[库存] 预扣减成功: orderId={}, skuId={}, qty={}, userId={}",
                        orderId, skuId, request.getQuantity(), request.getUserId());
                businessMetrics.recordPreDeduct("success");
                businessMetrics.recordPreDeductLatency(System.currentTimeMillis() - startTime);
                // L2: MQ 同步发 MySQL（失败则回滚 Redis 预扣）
                if (!sendInventoryEvent(orderId, skuId, request.getQuantity(), "PRE_DEDUCT")) {
                    rollbackPreDeduct(orderId, skuId, request.getQuantity());
                    throw new BizException(ResultCode.INTERNAL_ERROR, "库存扣减失败，请重试");
                }
                return true;
            }
            case 0 -> {
                businessMetrics.recordPreDeduct("insufficient");
                throw new BizException(ResultCode.STOCK_NOT_ENOUGH);
            }
            case -1 -> {
                log.warn("[库存] 重复预扣(幂等拦截): orderId={}, skuId={}", orderId, skuId);
                // 幂等：重复预扣不报错，视为成功（幂等占位保留）
                return true;
            }
            case -2 -> throw new BizException(ResultCode.SKU_STOCK_NOT_INITIALIZED, "库存未初始化");
            case -3 -> {
                // 04B：订单已终结（终结墓碑命中）——迟到/重投的预扣消息，直接跳过视为处理完成。
                // MySQL 幂等占位保留（idemOk=true）：后续重投在 insertPredeductIdem 处即被拦截。
                businessMetrics.recordPreDeduct("terminated");
                log.warn("[库存] 预扣减-订单已终结(墓碑命中), 跳过: orderId={}, skuId={}", orderId, skuId);
                return true;
            }
            default -> throw new BizException(ResultCode.INTERNAL_ERROR, "库存操作异常: result=" + result);
        }
    }

    // ==================== 确认扣减 ====================

    /**
     * 确认扣减（支付成功后调用；C-008 items 契约 + F12 标记窗口修复）
     * <p>
     * 语义：逐 (sku,order) 删除预扣记录（库存已在预扣时扣减，确认只需撕欠条）+ L2 CONFIRM 事件。
     * </p>
     * <p>
     * F12：**任一预期 SKU 的记录缺失**即写确认标记——迟到落盘的记录由超时任务改判"补确认"而非释放
     * （防已付款订单的货被放回；旧实现仅"全部缺失"才写标记，部分缺失窗口会释放迟到记录）。
     * </p>
     */
    public void confirmDeduct(ConfirmDeductRequest request) {
        Long orderId = request.getOrderId();
        List<StockItem> items = request.getItems();
        if (items == null || items.isEmpty()) {
            // 防御（保守方向）：无法定位记录 → 写确认标记（超时任务不释放任何迟到记录）
            writeConfirmedMarker(orderId);
            log.warn("[库存] 确认扣减-items缺失(写确认标记保守处理): orderId={}", orderId);
            return;
        }

        boolean anyMissing = false;
        for (StockItem item : items) {
            Long skuId = item.getSkuId();
            if (skuId == null) {
                anyMissing = true;
                continue;
            }
            // Lua 原子删除预扣记录（KEYS 同 {skuId} tag）
            Long result = stringRedisTemplate.execute(
                    confirmScript,
                    List.of(predeductKey(skuId, orderId), predeductIndexKey(skuId)),
                    String.valueOf(orderId));
            if (result == null || result == 0) {
                // 记录缺失（a.已确认 b.已释放 c.预扣还没落——支付确认反超）：计入 anyMissing
                anyMissing = true;
                continue;
            }
            log.info("[库存] 确认扣减: orderId={}, skuId={}, qty={}", orderId, skuId, result);
            businessMetrics.recordInventoryAction("confirm_ok"); // C-008 F9

            // L2: MQ 异步更新 MySQL（同步发送，失败保留 Outbox 等 Job 补发；消费端逻辑事件幂等兜底）
            if (!sendInventoryEvent(orderId, skuId, result.intValue(), "CONFIRM")) {
                log.error("[库存] 确认扣减MQ发送失败(依赖Outbox补发/L3对账): orderId={}, skuId={}", orderId, skuId);
            }
        }
        if (anyMissing) {
            writeConfirmedMarker(orderId);
            log.warn("[库存] 确认扣减-存在记录缺失(写确认标记, 超时任务将改判补确认): orderId={}", orderId);
        }
    }

    /** 写确认标记（04B：超时任务凭它把"已付款订单的迟到预扣记录"改判为补确认而非释放） */
    private void writeConfirmedMarker(Long orderId) {
        stringRedisTemplate.opsForValue().set(confirmedMarkerKey(orderId), "1",
                confirmedMarkerTtlSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 04B 收尾：订单是否已"确认"过（确认标记存在）。
     * <p>confirmDeduct 在预扣记录缺失时写入该标记；PreDeductTimeoutJob 释放前凭它区分
     * "已付款订单的迟到记录（补确认）"与"真弃单（释放）"。</p>
     */
    public boolean hasConfirmedMarker(Long orderId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(confirmedMarkerKey(orderId)));
    }

    // ==================== 释放库存 ====================

    /**
     * 释放库存（取消订单/超时未支付；C-008 items 契约）
     * <p>
     * 逐 (sku,order)：原子「检查记录 + 总是写墓碑」（拦截迟到/重投预扣），存在则回退库存 + L2 RELEASE 事件。
     * </p>
     */
    public void releaseStock(ReleaseStockRequest request) {
        Long orderId = request.getOrderId();
        List<StockItem> items = request.getItems();
        if (items == null || items.isEmpty()) {
            // 防御（保守方向）：无法定位记录 → 不释放（仅告警；迟到预扣由超时任务/TTL 兜底）
            log.error("[库存] 释放库存-items缺失(无法定位预扣记录, 不释放仅告警): orderId={}", orderId);
            return;
        }

        for (StockItem item : items) {
            Long skuId = item.getSkuId();
            if (skuId == null) {
                continue;
            }
            // 04B 终结墓碑：原子「检查预扣记录 + 总是写墓碑」（二次 Review S1：无条件写——
            // 仅在记录存在时写会遗留"先释放完 → 迟到预扣到达 → 预扣成功"的泄漏窗口）
            Long recordExists = stringRedisTemplate.execute(
                    markTerminatedScript,
                    List.of(predeductKey(skuId, orderId), terminatedTombstoneKey(skuId, orderId)),
                    String.valueOf(terminatedTombstoneTtlSeconds));
            if (recordExists == null || recordExists == 0) {
                log.warn("[库存] 释放库存-预扣记录不存在(已释放/已确认/从未预扣), 已写终结墓碑: orderId={}, skuId={}",
                        orderId, skuId);
                continue; // 幂等：已释放或已确认，不报错
            }

            // Lua 原子释放（KEYS 同 {skuId} tag）
            Long result = stringRedisTemplate.execute(
                    releaseScript,
                    List.of(stockKey(skuId), predeductKey(skuId, orderId), predeductIndexKey(skuId)),
                    String.valueOf(orderId));
            if (result == null || result == 0) {
                log.warn("[库存] 释放库存-并发释放/已确认(跳过): orderId={}, skuId={}", orderId, skuId);
                continue; // 防御：脚本检查后被并发释放/确认
            }
            log.info("[库存] 释放库存: orderId={}, skuId={}, qty={}", orderId, skuId, result);
            businessMetrics.recordInventoryAction("release_ok"); // C-008 F9

            // L2: MQ 异步更新 MySQL（同步发送，失败保留 Outbox 等 Job 补发）
            if (!sendInventoryEvent(orderId, skuId, result.intValue(), "RELEASE")) {
                log.error("[库存] 释放库存MQ发送失败(依赖Outbox补发/L3对账): orderId={}, skuId={}", orderId, skuId);
            }
        }
    }

    // ==================== 查询库存 ====================

    /**
     * 查询 SKU 可用库存
     * <p>
     * Cache-Aside：优先 Redis 单键；未命中读 MySQL（【N7 超卖防护】不回填——MySQL 是 L2 异步持久层，
     * 在途预扣尚未反映，盲目回填会把在途预扣"回滚"→ 超卖；Redis 数据缺失由 /reinit 显式重建）。
     * </p>
     */
    public StockVO getStock(Long skuId) {
        String stockStr = stringRedisTemplate.opsForValue().get(stockKey(skuId));

        if (stockStr != null) {
            // lockedStock 展示字段：5s 短缓存（允许秒级滞后，收敛靠 locked 对账/预扣链路以 DB 为准）
            Integer lockedStock = readLockedStockCached(skuId);
            return StockVO.builder()
                    .skuId(skuId)
                    .availableStock(Integer.parseInt(stockStr))
                    .lockedStock(lockedStock)
                    .initialized(true)
                    .build();
        }

        // Redis 未命中（Canal 删除了缓存，或 Redis 故障）→ 读 MySQL，不回填
        Inventory inventory = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BizException(ResultCode.SKU_NOT_FOUND, "库存记录不存在");
        }
        log.warn("[库存] Redis库存键缺失(可能Redis故障), 不回填MySQL防超卖, 需reinit重建: skuId={}", skuId);
        return StockVO.builder()
                .skuId(skuId)
                .availableStock(inventory.getAvailableStock())
                .lockedStock(inventory.getLockedStock())
                .initialized(false)
                .build();
    }

    // ==================== 私有方法 ====================

    /**
     * 发送库存变更事件到 MQ（同步发送；PRE_DEDUCT 失败则调用方回滚 Redis + 抛异常）
     * <p>
     * Outbox 模式：先写 Outbox 表（uk (order,sku,action)），发送成功后标记已发；
     * PRE_DEDUCT 失败作废 Outbox（防 Job 补发已回滚事件）；其余动作失败保留 Outbox 由 Job 补发
     * （消费端逻辑事件幂等兜底，不双扣）。
     * </p>
     *
     * @return true=发送成功, false=发送失败（PRE_DEDUCT 调用方需回滚 Redis）
     */
    private boolean sendInventoryEvent(Long orderId, Long skuId, int quantity, String action) {
        long eventId = IdWorker.getId();
        long eventTime = System.currentTimeMillis();
        try {
            // C-008 R7：eventTime 随行存储——Job 补发精确复用（事件级幂等键一致）
            inventoryMapper.insertOutboxEvent(eventId, orderId, skuId, quantity, action, eventTime);

            InventoryDeductEvent event = InventoryDeductEvent.builder()
                    .outboxId(eventId)
                    .orderId(orderId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .action(action)
                    .eventTime(eventTime)
                    .build();

            String payload = objectMapper.writeValueAsString(event);
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    INVENTORY_TOPIC + ":" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000 // 超时 3 秒
            );

            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                // 同步发送成功立即标记 Outbox 已发送（防 Job 重发已确认事件）
                // C-008 F2：按 (order,sku,action) 匹配（原按 eventId 在 dup 复用行场景 0 行静默失效）；
                // 消费端另有逻辑事件幂等兜底（L2 inbox / 完成态标记）
                inventoryMapper.markOutboxSent(orderId, skuId, action);
                log.debug("[库存] MQ发送成功: action={}, orderId={}, skuId={}",
                        action, orderId, skuId);
                return true;
            } else {
                log.error("[库存] MQ发送状态异常: action={}, orderId={}, skuId={}, status={}",
                        action, orderId, skuId, sendResult.getSendStatus());
                if ("PRE_DEDUCT".equals(action)) {
                    inventoryMapper.cancelOutboxEvent(orderId, skuId, action);
                }
                return false;
            }
        } catch (Exception e) {
            log.error("[库存] MQ发送异常: orderId={}, skuId={}, action={}", orderId, skuId, action, e);
            if ("PRE_DEDUCT".equals(action)) {
                inventoryMapper.cancelOutboxEvent(orderId, skuId, action);
            }
            return false;
        }
    }

    /**
     * 读取 lockedStock（5s Redis 短缓存）
     */
    private Integer readLockedStockCached(Long skuId) {
        String key = "inventory:locked:cache:" + skuId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Integer.valueOf(cached);
            }
        } catch (Exception e) {
            log.warn("[库存] locked 缓存读取失败(降级查DB): skuId={}", skuId);
        }
        Inventory inv = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>()
                        .select(Inventory::getLockedStock)
                        .eq(Inventory::getSkuId, skuId));
        Integer locked = inv != null ? inv.getLockedStock() : null;
        if (locked != null) {
            try {
                stringRedisTemplate.opsForValue().set(key, String.valueOf(locked), 5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 缓存写失败不影响返回
            }
        }
        return locked;
    }

    boolean skuExists(Long skuId) {
        try {
            com.myxhs.common.response.R<com.myxhs.inventory.feign.ProductFeignClient.SkuDTO> response = productFeignClient.getSkuDetail(skuId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return false;
            }
            com.myxhs.inventory.feign.ProductFeignClient.SkuDTO sku = response.getData();
            return sku.getStatus() != null && sku.getStatus() == 1;
        } catch (Exception e) {
            log.warn("[库存] SKU存在性校验失败: skuId={}", skuId, e);
            return true;
        }
    }

    /**
     * Rebuild Redis stock key from MySQL (T-066 self-heal).
     * <p>
     * canal cache-eviction 删除 Redis 库存键（DELETE 事件）无回填 → preDeduct fail-closed →
     * 订单已提交但库存不扣。此处重建（total = available + locked，N7 语义）；
     * SETNX/Redisson 锁防并发重建。
     * </p>
     */
    private boolean rebuildStockFromDb(Long skuId) {
        String lockKey = "inventory:rebuild:lock:" + skuId;
        org.redisson.api.RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                return false; // 其他线程重建中，本线程失败（MQ 重试会再进来）
            }
            // 双检查：可能已被其他线程重建
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey(skuId)))) {
                return true;
            }
            Inventory inv = inventoryMapper.selectOne(
                    new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
            if (inv == null || (inv.getDeleted() != null && inv.getDeleted() == 1)) {
                log.warn("[库存] 自愈重建失败(MySQL无记录): skuId={}", skuId);
                return false;
            }
            int total = inv.getAvailableStock() + inv.getLockedStock();
            stringRedisTemplate.opsForValue().set(stockKey(skuId), String.valueOf(total));
            log.info("[库存] 自愈重建完成: skuId={}, total={}", skuId, total);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[库存] 自愈重建获取锁被中断: skuId={}", skuId);
            return false;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 退款回补库存（T-071；C-008 原子化）
     * <p>
     * 全额/部分退款后调用：confirm 已清预扣记录 → release 无记录可退（G5 实证退款不退库存）。
     * 独立语义：Redis stock +qty + MQ REFUND_RESTORE（L2 同步 MySQL available_stock）。
     * </p>
     * <p>
     * C-008：累计幂等（refund 键记录"已受理的应回补数量"）+ 库存 INCRBY **单脚本原子**——
     * 崩溃不存在"推进了但没补"窗口（旧实现先推进累计键后加库存，kill -9 后 delta=0 永久漏补）；
     * 重复调用/重试 delta=0 跳过；分次退货不漏补；叠加路径后到者 delta=0 不重复补。
     * </p>
     */
    public void refundRestore(com.myxhs.inventory.dto.request.RefundRestoreRequest request) {
        Long orderId = request.getOrderId();
        Long skuId = request.getSkuId();
        int quantity = request.getQuantity();

        // 防御（T-078）：库存键缺失时先自愈重建（canal 延迟删除/Redis 故障场景），
        // 否则原子脚本返回 -2（脚本内二次防御：库存键缺失不动累计键）
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey(skuId)))) {
            if (!rebuildStockFromDb(skuId)) {
                log.error("[库存退款回补] Redis缺失且MySQL无记录, 放弃回补: orderId={}, skuId={}", orderId, skuId);
                return;
            }
        }

        Long delta;
        try {
            delta = stringRedisTemplate.execute(refundRestoreScript,
                    java.util.List.of(refundKey(skuId, orderId), stockKey(skuId)),
                    String.valueOf(quantity),
                    String.valueOf(java.time.Duration.ofDays(30).getSeconds()));
        } catch (Exception e) {
            // Redis 不可用：不能直接返回（调用方会当作回补成功而不再重试）——落补偿表由 Job 重试
            log.error("[库存退款回补] 原子脚本执行失败, 落补偿表待重试: orderId={}, skuId={}", orderId, skuId, e);
            recordRefundRestoreCompensation(orderId, skuId, quantity, request.getUserId(), e.getMessage());
            return;
        }
        if (delta == null || delta == 0) {
            log.info("[库存退款回补] 幂等跳过（本次请求 {} 件, 累计已受理）: orderId={}, skuId={}",
                    quantity, orderId, skuId);
            return;
        }
        if (delta < 0) {
            // -2：库存键缺失（检查后并发丢失，极罕见）→ 落补偿表（保守：不丢回补）
            log.error("[库存退款回补] 库存键缺失(并发窗口), 落补偿表待重试: orderId={}, skuId={}", orderId, skuId);
            recordRefundRestoreCompensation(orderId, skuId, quantity, request.getUserId(), "stock-key-missing");
            return;
        }
        log.info("[库存退款回补] Redis回补: orderId={}, skuId={}, delta={}", orderId, skuId, delta);
        businessMetrics.recordInventoryAction("refund_ok"); // C-008 F9

        // MQ 同步 MySQL（available_stock +delta；失败保留 Outbox 由 Job 补发）
        if (!sendInventoryEvent(orderId, skuId, delta.intValue(), "REFUND_RESTORE")) {
            log.error("[库存退款回补] MQ发送失败(依赖Outbox/对账兜底): orderId={}, skuId={}", orderId, skuId);
        }
    }

    /**
     * 写"退款回补"补偿记录（type=2），由 InventoryCompensationJob 自动重试；已有待处理记录则不重复堆积
     */
    private void recordRefundRestoreCompensation(Long orderId, Long skuId, int quantity, Long userId, String reason) {
        try {
            if (inventoryMapper.countPendingCompensation(orderId, skuId, COMPENSATION_TYPE_REFUND_RESTORE) == 0) {
                inventoryMapper.insertCompensationWithType(orderId, skuId, quantity,
                        COMPENSATION_TYPE_REFUND_RESTORE, userId, reason);
            }
        } catch (Exception ce) {
            log.error("[库存退款回补] 补偿记录写入失败, 需人工介入: orderId={}, skuId={}", orderId, ce);
        }
    }

    /**
     * 回滚 Redis 预扣库存（MQ 发送失败时调用；C-008 单键家族）
     */
    private void rollbackPreDeduct(Long orderId, Long skuId, int quantity) {
        try {
            stringRedisTemplate.execute(
                    releaseScript,
                    List.of(stockKey(skuId), predeductKey(skuId, orderId), predeductIndexKey(skuId)),
                    String.valueOf(orderId)
            );
            log.info("[库存] Redis预扣回滚成功: orderId={}, skuId={}, qty={}", orderId, skuId, quantity);
        } catch (Exception e) {
            log.error("[库存] Redis预扣回滚失败(已写入补偿表): orderId={}, skuId={}, qty={}",
                    orderId, skuId, quantity, e);
            // 写入补偿表，由 InventoryCompensationJob 异步重试
            try {
                inventoryMapper.insertCompensation(orderId, skuId, quantity, e.getMessage());
            } catch (Exception ex) {
                log.error("[库存] 补偿表写入也失败, 需人工介入: orderId={}, skuId={}", orderId, skuId, ex);
            }
        }
    }

    // ==================== 重建 ====================

    /**
     * 重新初始化库存（管理后台调用）
     * <p>
     * 清除该 SKU 的 Redis 库存键，从 MySQL 恢复真实库存（total = available + locked）。
     * 旧分桶键（bucket:*）由部署说明一次性清理。
     * </p>
     */
    public void reinitStock(Long skuId) {
        Inventory inventory = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "SKU库存不存在");
        }

        int totalStock = inventory.getAvailableStock() + inventory.getLockedStock();

        stringRedisTemplate.delete(stockKey(skuId));
        stringRedisTemplate.opsForValue().set(stockKey(skuId), String.valueOf(totalStock));

        log.info("[库存] 重新初始化完成: skuId={}, total={}", skuId, totalStock);
    }
}
