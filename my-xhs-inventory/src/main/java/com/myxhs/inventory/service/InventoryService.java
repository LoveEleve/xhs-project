package com.myxhs.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.event.InventoryDeductEvent;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.InventoryInitRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.dto.response.StockVO;
import com.myxhs.inventory.entity.Inventory;
import com.myxhs.inventory.mapper.InventoryMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 库存服务
 * <p>
 * 核心设计：分桶预扣减 + 三级扣减保证
 * </p>
 * <p>
 * 分桶原理：
 * 将 1 个 SKU 的库存拆分到 N 个 Redis Key（桶），扣减时按 userId % N 路由到固定桶。
 * 分散单 Key 热点，支持 10 万+ QPS 的秒杀场景。
 * </p>
 * <p>
 * 三级扣减保证：
 * L1: Redis 分桶预扣（Lua 原子操作，毫秒级响应）
 * L2: MQ 异步扣 MySQL（保证持久化，Consumer 幂等）
 * L3: 定时对账修复（Redis ↔ MySQL 最终一致）
 * </p>
 * <p>
 * Redis Key 设计（使用 {skuId} hash tag 保证 total/bucket 在 Cluster 下同 slot）：
 * - 分桶库存：inventory:{skuId}:bucket:{bucketNo}（String，值=该桶库存数）
 * - 总可用库存：inventory:{skuId}:total（String，值=所有桶库存之和）
 * - 预扣记录：inventory:prededuct:{orderId}（Hash，field=skuId，value=quantity，按订单独立）
 * - 分桶数量：inventory:bucket:count:{skuId}（String，值=桶数）
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
    private final DefaultRedisScript<Long> confirmScript;

    @Value("${inventory.bucket.default-count:2}")
    private int defaultBucketCount;

    @Value("${inventory.bucket.hot-count:8}")
    private int hotBucketCount;

    @Value("${inventory.prededuct.expire-seconds:1800}")
    private int preDeductExpireSeconds;

    private final com.myxhs.inventory.hot.HotSkuDetector hotSkuDetector;

    /** 【M9】异步扩容线程池（核心2，最大4，队列50，CallerRunsPolicy 防 OOM） */
    private static final java.util.concurrent.ExecutorService inventoryAsyncExecutor =
            new java.util.concurrent.ThreadPoolExecutor(2, 4, 60, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(50),
                    r -> { Thread t = new Thread(r, "inventory-async"); t.setDaemon(true); return t; },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * JVM 退出时关闭异步线程池，防止队列中未完成任务丢失
     */
    @PreDestroy
    public void shutdownAsyncExecutor() {
        log.info("[库存] 关闭异步扩容线程池...");
        inventoryAsyncExecutor.shutdown();
        try {
            if (!inventoryAsyncExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[库存] 线程池未能在10秒内终止，强制关闭");
                inventoryAsyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("[库存] 线程池关闭被中断，强制关闭");
            inventoryAsyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[库存] 异步扩容线程池已关闭");
    }

    /**
     * Redis Key 前缀
     * 【修复M14】bucket 和 total 使用 {skuId} 作为 hash tag，保证 prededuct.lua 中操作的
     * total + buckets Key 在 Cluster 下同 slot。prededuct Key 按 orderId 组织（独立 slot）。
     */
    private static final String BUCKET_KEY_PREFIX = "inventory:{%d}:bucket:";
    private static final String TOTAL_KEY_PREFIX = "inventory:{%d}:total";
    private static final String PREDEDUCT_KEY_PREFIX = "inventory:prededuct:";
    private static final String BUCKET_COUNT_KEY_PREFIX = "inventory:bucket:count:";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_PREFIX, skuId); }
    private static String predeductKey(Long orderId) { return PREDEDUCT_KEY_PREFIX + orderId; }
    private static String bucketKey(Long skuId, int bucketNo) { return String.format(BUCKET_KEY_PREFIX, skuId) + bucketNo; }

    /** MQ Topic */
    private static final String INVENTORY_TOPIC = "INVENTORY_TOPIC";

    // ==================== 库存初始化 ====================

    /**
     * 库存初始化（DB → Redis 分桶）
     * <p>
     * 将 MySQL 中的库存均匀分配到 N 个 Redis 桶中。
     * 分配策略：每桶 = total / N，余数分给桶0。
     * </p>
     * <p>
     * 幂等性：如果已初始化（total Key 存在），抛出异常。
     * 重新初始化需要先手动清除 Redis Key。
     * </p>
     */
    public void initStock(InventoryInitRequest request) {
        Long skuId = request.getSkuId();
        int totalStock = request.getTotalStock();
        int bucketCount = request.getBucketCount() != null ? request.getBucketCount() : defaultBucketCount;

        // 幂等检查：使用 SETNX 原子操作（比 hasKey + set 两步更安全）
        // 如果 totalKey 已存在，setIfAbsent 返回 false，保证并发初始化只有一个成功
        String totalKey = totalKey(skuId);
        Boolean setSuccess = stringRedisTemplate.opsForValue().setIfAbsent(totalKey, String.valueOf(totalStock));
        if (Boolean.FALSE.equals(setSuccess)) {
            throw new BizException(ResultCode.PARAM_INVALID, "库存已初始化，如需重新初始化请先清除");
        }

        // 1. 确保 MySQL 中有库存记录
        Inventory inventory = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            // 自动创建库存记录
            inventory = new Inventory();
            inventory.setSkuId(skuId);
            inventory.setAvailableStock(totalStock);
            inventory.setLockedStock(0);
            inventoryMapper.insert(inventory);
            log.info("[库存] 自动创建MySQL记录: skuId={}, stock={}", skuId, totalStock);
        } else {
            // 以请求的 totalStock 为准更新 MySQL
            inventory.setAvailableStock(totalStock);
            inventory.setLockedStock(0);
            inventoryMapper.updateById(inventory);
        }

        // 2. 均匀分配到 N 个桶
        int perBucket = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;

        for (int i = 0; i < bucketCount; i++) {
            int bucketStock = perBucket + (i == 0 ? remainder : 0);
            stringRedisTemplate.opsForValue().set(bucketKey(skuId, i), String.valueOf(bucketStock));
        }

        // 3. 设置总库存和桶数
        stringRedisTemplate.opsForValue().set(totalKey, String.valueOf(totalStock));
        stringRedisTemplate.opsForValue().set(BUCKET_COUNT_KEY_PREFIX + skuId, String.valueOf(bucketCount));

        log.info("[库存] 初始化完成: skuId={}, total={}, buckets={}, perBucket={}, remainder={}",
                skuId, totalStock, bucketCount, perBucket, remainder);
    }

    // ==================== 预扣减 ====================

    /**
     * 分桶预扣减（Lua 脚本原子操作）
     * <p>
     * 这是库存扣减的 L1 层——Redis 预扣。
     * Lua 脚本在 Redis 单线程中执行，天然保证原子性，不会超卖。
     * </p>
     * <p>
     * 扣减流程：
     * 1. 幂等检查（同一订单不能重复预扣）
     * 2. 快速检查总库存
     * 3. 按 userId 路由到固定桶
     * 4. 路由桶不足时遍历其他桶（桶间均衡）
     * 5. 扣减成功后写预扣记录（30分钟过期）
     * 6. MQ 异步通知扣 MySQL（L2）
     * </p>
     */
    public void preDeduct(PreDeductRequest request) {
        Long skuId = request.getSkuId();
        Long orderId = request.getOrderId();

        // 【M9】检查是否正在扩容（暂停标记存在时延迟重试）
        String pauseKey = "inventory:paused:" + skuId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(pauseKey))) {
            log.info("[库存] SKU正在扩容中，延迟消费: skuId={}", skuId);
            throw new RuntimeException("SKU扩容中，稍后重试");
        }

        // 获取分桶数
        String bucketCountStr = stringRedisTemplate.opsForValue().get(BUCKET_COUNT_KEY_PREFIX + skuId);
        if (bucketCountStr == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "库存未初始化");
        }
        int bucketCount = Integer.parseInt(bucketCountStr);

        // 【M9】热点检测（异步触发扩容，不阻塞当前请求）
        if (hotSkuDetector.recordAndCheck(skuId) && bucketCount < hotBucketCount) {
            final int currentBuckets = bucketCount;
            inventoryAsyncExecutor.execute(() -> {
                try {
                    resizeBuckets(skuId, currentBuckets, hotBucketCount);
                } catch (Exception e) {
                    log.warn("[库存] 热点扩容失败: skuId={}, {}→{}", skuId, currentBuckets, hotBucketCount, e);
                }
            });
        }

        // 【修复M14】Lua 脚本原子预扣减：将所有桶 Key 通过 KEYS 参数传入（Cluster 兼容）
        String totalKeyStr = totalKey(skuId);
        String predeductKeyStr = predeductKey(orderId);

        // 构建完整 KEYS 列表：[totalKey, predeductKey, bucket:0, bucket:1, ..., bucket:N-1]
        List<String> keys = new ArrayList<>(2 + bucketCount);
        keys.add(totalKeyStr);
        keys.add(predeductKeyStr);
        for (int i = 0; i < bucketCount; i++) {
            keys.add(bucketKey(skuId, i));
        }

        Long result = stringRedisTemplate.execute(
                preDeductScript,
                keys,
                String.valueOf(skuId),
                String.valueOf(orderId),
                String.valueOf(request.getQuantity()),
                String.valueOf(bucketCount),
                String.valueOf(request.getUserId()),
                String.valueOf(preDeductExpireSeconds)
        );

        if (result == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "库存操作失败");
        }

        switch (result.intValue()) {
            case 1 -> {
                log.info("[库存] 预扣减成功: orderId={}, skuId={}, qty={}, userId={}",
                        orderId, skuId, request.getQuantity(), request.getUserId());
                // L2: MQ 同步发 MySQL（失败则回滚 Redis 预扣）
                if (!sendInventoryEvent(orderId, skuId, request.getQuantity(), "PRE_DEDUCT")) {
                    rollbackPreDeduct(orderId, skuId, request.getQuantity());
                    throw new BizException(ResultCode.INTERNAL_ERROR, "库存扣减失败，请重试");
                }
            }
            case 0 -> throw new BizException(ResultCode.STOCK_NOT_ENOUGH);
            case -1 -> {
                log.warn("[库存] 重复预扣(幂等拦截): orderId={}, skuId={}", orderId, skuId);
                // 幂等：重复预扣不报错，视为成功
            }
            case -2 -> throw new BizException(ResultCode.PARAM_INVALID, "库存未初始化");
            default -> throw new BizException(ResultCode.INTERNAL_ERROR, "库存操作异常: result=" + result);
        }
    }

    // ==================== 确认扣减 ====================

    /**
     * 确认扣减（支付成功后调用）
     * <p>
     * Redis 层面：删除预扣记录（库存已在预扣时扣减，无需再变动）
     * MySQL 层面：MQ 异步将 locked_stock 减少（确认扣减）
     * </p>
     */
    public void confirmDeduct(ConfirmDeductRequest request) {
        Long orderId = request.getOrderId();
        String predeductKey = predeductKey(orderId);

        // 获取预扣记录中的所有 SKU
        var entries = stringRedisTemplate.opsForHash().entries(predeductKey);
        if (entries.isEmpty()) {
            log.warn("[库存] 确认扣减-预扣记录不存在(可能已确认或已过期): orderId={}", orderId);
            return; // 幂等：已确认或已过期，不报错
        }

        // 逐个 SKU 确认
        for (var entry : entries.entrySet()) {
            String fieldName = entry.getKey().toString();
            // 过滤 :bucket 辅助字段（同 releaseStock 逻辑）
            if (fieldName.contains(":bucket")) {
                continue;
            }

            Long skuId = Long.parseLong(fieldName);
            int quantity = Integer.parseInt(entry.getValue().toString());

            // Lua 原子删除预扣记录
            Long result = stringRedisTemplate.execute(
                    confirmScript,
                    List.of(predeductKey),
                    fieldName
            );

            log.info("[库存] 确认扣减: orderId={}, skuId={}, qty={}, result={}",
                    orderId, skuId, quantity, result);

            // L2: MQ 异步更新 MySQL（locked_stock 减少）
            sendInventoryEvent(orderId, skuId, quantity, "CONFIRM");
        }
    }

    // ==================== 释放库存 ====================

    /**
     * 释放库存（取消订单/超时未支付）
     * <p>
     * Redis 层面：预扣的库存回退到桶0 + 总库存回退 + 删除预扣记录
     * MySQL 层面：MQ 异步将 locked_stock 回退到 available_stock
     * </p>
     * <p>
     * 为什么回退到桶0而不是原来的桶？
     * 预扣记录中没有记录"从哪个桶扣的"（为了减少存储开销）。
     * 回退到桶0不影响正确性（总库存一致），只是桶间分布可能略有偏差。
     * 对账任务会定期检查并重新均衡。
     * </p>
     */
    public void releaseStock(ReleaseStockRequest request) {
        Long orderId = request.getOrderId();
        String predeductKey = predeductKey(orderId);

        // 获取预扣记录中的所有 SKU
        var entries = stringRedisTemplate.opsForHash().entries(predeductKey);
        if (entries.isEmpty()) {
            log.warn("[库存] 释放库存-预扣记录不存在(可能已释放或已确认): orderId={}", orderId);
            return; // 幂等：已释放或已确认，不报错
        }

        // 逐个 SKU 释放
        for (var entry : entries.entrySet()) {
            String fieldName = entry.getKey().toString();
            // 【修复M12】过滤 :bucket 辅助字段，只处理 skuId 字段
            if (fieldName.contains(":bucket")) {
                continue;
            }

            Long skuId = Long.parseLong(fieldName);
            int quantity = Integer.parseInt(entry.getValue().toString());

            // 获取来源桶号（release.lua 需要桶 Key 作为 KEYS[3]）
            Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, fieldName + ":bucket");
            int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;

            String totalKeyStr = totalKey(skuId);
            String bucketKeyStr = bucketKey(skuId, bucketNo);

            // Lua 原子释放（传入 totalKey + predeductKey + bucketKey）
            Long result = stringRedisTemplate.execute(
                    releaseScript,
                    List.of(totalKeyStr, predeductKey, bucketKeyStr),
                    fieldName
            );

            log.info("[库存] 释放库存: orderId={}, skuId={}, qty={}, bucket={}, result={}",
                    orderId, skuId, quantity, bucketNo, result);

            // L2: MQ 异步更新 MySQL（locked_stock → available_stock）
            sendInventoryEvent(orderId, skuId, quantity, "RELEASE");
        }
    }

    // ==================== 查询库存 ====================

    /**
     * 查询 SKU 可用库存
     * <p>
     * Cache-Aside 模式：优先从 Redis 读取，Redis 未命中则从 MySQL 读取并回填。
     * 配合 Canal 缓存失效方案：Canal 删缓存后，本方法负责回填最新值。
     * </p>
     * <p>
     * 回填策略：
     * - 如果该 SKU 之前已初始化过（MySQL 有记录且 Redis 有分桶数量 Key 的残留或对账标记），
     *   则从 MySQL 读取最新值并覆盖写入 Redis
     * - 如果该 SKU 从未初始化过，则只返回 MySQL 数据，不回填
     * </p>
     * <p>
     * 分布式安全：
     * 多个读请求同时发现缓存未命中时，都会执行回填。
     * 由于回填是从 MySQL 读取同一份数据再写入 Redis，结果是一致的（写入相同值）。
     * 使用 SET（覆盖写）而非 SETNX，保证 Canal 删缓存后一定能回填成功。
     * </p>
     */
    public StockVO getStock(Long skuId) {
        String totalKey = totalKey(skuId);
        String totalStr = stringRedisTemplate.opsForValue().get(totalKey);

        if (totalStr != null) {
            // Redis 缓存命中
            String bucketCountStr = stringRedisTemplate.opsForValue().get(BUCKET_COUNT_KEY_PREFIX + skuId);
            int bucketCount = bucketCountStr != null ? Integer.parseInt(bucketCountStr) : defaultBucketCount;

            return StockVO.builder()
                    .skuId(skuId)
                    .availableStock(Integer.parseInt(totalStr))
                    .bucketCount(bucketCount)
                    .initialized(true)
                    .build();
        }

        // Redis 缓存未命中（可能是 Canal 删除了缓存，或者首次访问）
        Inventory inventory = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BizException(ResultCode.SKU_NOT_FOUND, "库存记录不存在");
        }

        // 尝试从 MySQL 回填 Redis
        // 检查是否曾经初始化过：如果分桶数量 Key 存在，说明之前初始化过
        String bucketCountKey = BUCKET_COUNT_KEY_PREFIX + skuId;
        String bucketCountStr = stringRedisTemplate.opsForValue().get(bucketCountKey);

        if (bucketCountStr != null) {
            // 之前初始化过，Canal 删除了总库存 Key 但分桶数量 Key 可能还在
            // 从 MySQL 回填 Redis 总库存和分桶
            int bucketCount = Integer.parseInt(bucketCountStr);
            reloadStockToRedis(skuId, inventory.getAvailableStock(), bucketCount);
            log.info("[库存] Canal缓存失效后回填: skuId={}, stock={}, buckets={}",
                    skuId, inventory.getAvailableStock(), bucketCount);
        } else {
            // 检查 Canal 版本号 Key 是否存在（Canal 删了所有 Key 的场景）
            String versionKey = "inventory:canal:version:" + skuId;
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(versionKey))) {
                // Canal 曾经处理过该 SKU，说明之前初始化过但被 Canal 完全清除了
                int bucketCount = defaultBucketCount;
                reloadStockToRedis(skuId, inventory.getAvailableStock(), bucketCount);
                log.info("[库存] Canal全量缓存清除后回填: skuId={}, stock={}, buckets={}",
                        skuId, inventory.getAvailableStock(), bucketCount);
            }
            // 否则：从未初始化过，只返回 MySQL 数据，不回填
        }

        return StockVO.builder()
                .skuId(skuId)
                .availableStock(inventory.getAvailableStock())
                .lockedStock(inventory.getLockedStock())
                .initialized(bucketCountStr != null || Boolean.TRUE.equals(
                        stringRedisTemplate.hasKey("inventory:canal:version:" + skuId)))
                .build();
    }

    /**
     * 从 MySQL 重新加载库存到 Redis（Canal 缓存失效后回填）
     * <p>
     * 与 initStock 的区别：
     * - initStock：首次初始化，使用 SETNX 幂等检查
     * - reloadStockToRedis：缓存失效后回填，使用 SET 覆盖写入（保证一定能回填成功）
     * </p>
     * <p>
     * 回填原子性：
     * 使用 Redis Pipeline 将总库存、各桶库存、桶数量一次性写入，
     * 减少网络往返次数。Pipeline 不是事务，中间可能穿插其他命令，
     * 但由于回填是从 MySQL 读取的一致快照，即使中间有预扣减操作，
     * 预扣减的 Lua 脚本会检查库存是否足够，不会超卖。
     * </p>
     *
     * @param skuId       SKU ID
     * @param totalStock  MySQL 中的可用库存
     * @param bucketCount 分桶数
     */
    private void reloadStockToRedis(Long skuId, int totalStock, int bucketCount) {
        // 均匀分配到各桶
        int perBucket = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;

        // Pipeline 批量写入
        stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    var stringConnection = connection.stringCommands();
                    byte[] totalKeyBytes = (totalKey(skuId)).getBytes();
                    byte[] bucketCountKeyBytes = (BUCKET_COUNT_KEY_PREFIX + skuId).getBytes();

                    // 写入总库存
                    stringConnection.set(totalKeyBytes, String.valueOf(totalStock).getBytes());
                    // 写入分桶数量
                    stringConnection.set(bucketCountKeyBytes, String.valueOf(bucketCount).getBytes());

                    // 写入各桶库存
                    for (int i = 0; i < bucketCount; i++) {
                        int bucketStock = perBucket + (i == 0 ? remainder : 0);
                        byte[] bucketKeyBytes = bucketKey(skuId, i).getBytes();
                        stringConnection.set(bucketKeyBytes, String.valueOf(bucketStock).getBytes());
                    }

                    return null;
                }
        );
    }

    // ==================== 私有方法 ====================

    /**
     * 发送库存变更事件到 MQ（同步发送，失败则回滚 Redis + 抛异常）
     * <p>
     * 为什么用同步而非异步？
     * 异步发送失败时，Redis 库存已扣但 MySQL 永远不会更新，导致数据不一致。
     * 同步发送失败时可以立即回滚 Redis 库存，保证一致性。
     * </p>
     *
     * @return true=发送成功, false=发送失败(调用方需回滚Redis)
     */
    private boolean sendInventoryEvent(Long orderId, Long skuId, int quantity, String action) {
        try {
            InventoryDeductEvent event = InventoryDeductEvent.builder()
                    .orderId(orderId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .action(action)
                    .build();

            String payload = objectMapper.writeValueAsString(event);
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    INVENTORY_TOPIC + ":" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000 // 超时 3 秒
            );

            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.debug("[库存] MQ发送成功: action={}, orderId={}, skuId={}",
                        action, orderId, skuId);
                return true;
            } else {
                log.error("[库存] MQ发送状态异常: action={}, orderId={}, skuId={}, status={}",
                        action, orderId, skuId, sendResult.getSendStatus());
                return false;
            }
        } catch (Exception e) {
            log.error("[库存] MQ发送异常: orderId={}, skuId={}, action={}", orderId, skuId, action, e);
            return false;
        }
    }

    /**
     * 回滚 Redis 预扣库存（MQ 发送失败时调用）
     */
    private void rollbackPreDeduct(Long orderId, Long skuId, int quantity) {
        try {
            String predeductKeyStr = predeductKey(orderId);
            String totalKeyStr = totalKey(skuId);

            // 获取来源桶号（与 releaseStock 逻辑一致）
            Object bucketNoObj = stringRedisTemplate.opsForHash()
                    .get(predeductKeyStr, skuId + ":bucket");
            int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;
            String bucketKeyStr = bucketKey(skuId, bucketNo);

            stringRedisTemplate.execute(
                    releaseScript,
                    List.of(totalKeyStr, predeductKeyStr, bucketKeyStr),
                    String.valueOf(skuId)
            );
            log.info("[库存] Redis预扣回滚成功: orderId={}, skuId={}, qty={}", orderId, skuId, quantity);
        } catch (Exception e) {
            log.error("[库存] Redis预扣回滚失败(需人工介入): orderId={}, skuId={}, qty={}",
                    orderId, skuId, quantity, e);
        }
    }

    // ==================== M9：动态分桶 ====================

    /**
     * 分桶平滑扩容（热点检测触发）
     * <p>
     * 流程：暂停预扣 → 收集各桶库存 → 按新桶数重新分配 → 恢复预扣。
     * 全过程数据无损，暂停窗口内预扣失败由 MQ 重试兜底。
     * </p>
     */
    public void resizeBuckets(Long skuId, int oldBucketCount, int newBucketCount) {
        String lockKey = "inventory:resize:" + skuId;

        // Redis SETNX 分布式锁（10s 过期兜底）
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 10, java.util.concurrent.TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            log.info("[库存] 扩容操作进行中，跳过: skuId={}", skuId);
            return;
        }

        try {
            // 1. 暂停预扣
            String pauseKey = "inventory:paused:" + skuId;
            stringRedisTemplate.opsForValue().set(pauseKey, "1", 30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                // 2. 收集当前各桶库存
                int totalStock = 0;
                for (int i = 0; i < oldBucketCount; i++) {
                    String val = stringRedisTemplate.opsForValue().get(bucketKey(skuId, i));
                    totalStock += (val != null ? Integer.parseInt(val) : 0);
                }

                // 3. 按新桶数重新分配
                final int perBucket = totalStock / newBucketCount;
                final int remainder = totalStock % newBucketCount;
                final int finalTotal = totalStock;

                // 4. Pipeline 原子写入
                stringRedisTemplate.executePipelined(
                        (RedisCallback<Object>) connection -> {
                            var cmd = connection.stringCommands();
                            for (int i = 0; i < newBucketCount; i++) {
                                int stock = perBucket + (i == 0 ? remainder : 0);
                                cmd.set(bucketKey(skuId, i).getBytes(),
                                        String.valueOf(stock).getBytes());
                            }
                            for (int i = newBucketCount; i < oldBucketCount; i++) {
                                connection.keyCommands().del(bucketKey(skuId, i).getBytes());
                            }
                            cmd.set((BUCKET_COUNT_KEY_PREFIX + skuId).getBytes(),
                                    String.valueOf(newBucketCount).getBytes());
                            cmd.set(totalKey(skuId).getBytes(),
                                    String.valueOf(finalTotal).getBytes());
                            return null;
                        });

                log.info("[库存] 分桶扩容完成: skuId={}, {}桶→{}桶, total={}",
                        skuId, oldBucketCount, newBucketCount, totalStock);
            } finally {
                stringRedisTemplate.delete(pauseKey);
            }
        } catch (Exception e) {
            log.error("[库存] 扩容异常: skuId={}", skuId, e);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * 重新初始化库存（管理后台调用）
     * <p>
     * 清除该 SKU 的所有 Redis Key，从 MySQL 恢复真实库存后重新分桶。
     */
    public void reinitStock(Long skuId, int newBucketCount) {
        Inventory inventory = inventoryMapper.selectOne(
                new LambdaQueryWrapper<Inventory>().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "SKU库存不存在");
        }

        int totalStock = inventory.getAvailableStock() + inventory.getLockedStock();

        // 清除所有 Redis Key
        String totalKeyStr = totalKey(skuId);
        Set<String> bucketKeys = stringRedisTemplate.keys(
                String.format("inventory:{%d}:bucket:*", skuId));
        if (bucketKeys != null && !bucketKeys.isEmpty()) {
            stringRedisTemplate.delete(bucketKeys);
        }
        stringRedisTemplate.delete(totalKeyStr);
        stringRedisTemplate.delete(BUCKET_COUNT_KEY_PREFIX + skuId);
        stringRedisTemplate.delete("inventory:paused:" + skuId);

        // 重新初始化
        int perBucket = totalStock / newBucketCount;
        int remainder = totalStock % newBucketCount;

        for (int i = 0; i < newBucketCount; i++) {
            int stock = perBucket + (i == 0 ? remainder : 0);
            stringRedisTemplate.opsForValue().set(bucketKey(skuId, i), String.valueOf(stock));
        }
        stringRedisTemplate.opsForValue().set(totalKeyStr, String.valueOf(totalStock));
        stringRedisTemplate.opsForValue().set(BUCKET_COUNT_KEY_PREFIX + skuId,
                String.valueOf(newBucketCount));

        log.info("[库存] 重新初始化完成: skuId={}, total={}, buckets={}", skuId, totalStock, newBucketCount);
    }
}
