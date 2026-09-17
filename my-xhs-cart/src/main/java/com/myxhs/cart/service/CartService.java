package com.myxhs.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.dto.event.CartSyncEvent;
import com.myxhs.cart.dto.request.CartAddRequest;
import com.myxhs.cart.dto.request.CartCheckRequest;
import com.myxhs.cart.dto.request.CartMergeRequest;
import com.myxhs.cart.dto.request.CartUpdateQuantityRequest;
import com.myxhs.cart.dto.response.CartItemVO;
import com.myxhs.cart.dto.response.CartListVO;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import com.myxhs.cart.feign.ProductFeignClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 购物车服务
 * <p>
 * 核心设计：
 * 1. Redis 三结构协同：Hash(商品+数量) + Set(选中状态) + ZSet(加购时间排序)
 * 2. Redis 为权威数据源，MQ 异步持久化到 MySQL（防 Redis 故障丢数据）
 * 3. Feign 调 Product 服务获取商品详情，标记失效商品
 * </p>
 * <p>
 * 原子性保证（Lua 脚本）：
 * - 加入购物车：检查上限 + HINCRBY + 截断 + SADD + ZADD 一次网络往返
 * - 删除商品：HDEL + SREM + ZREM 三结构原子删除
 * - 对比社交服务的 follow_self.lua / follow_target.lua，购物车同样需要多命令原子执行
 * </p>
 * <p>
 * Redis Key 设计（使用 {userId} 作为 hash tag，保证三 Key 在 Cluster 下同 slot）：
 * - 商品数量：myxhs:cart:{userId}:items（Hash，field=skuId，value=quantity）
 * - 选中状态：myxhs:cart:{userId}:checked（Set，member=skuId）
 * - 加购排序：myxhs:cart:{userId}:sort（ZSet，member=skuId，score=timestamp）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** 事件序列号 — 保证同服务实例内事件时间戳严格递增，防同毫秒 C-05 误判跳过 */
    private static final java.util.concurrent.atomic.AtomicLong EVENT_SEQ = new java.util.concurrent.atomic.AtomicLong(0);

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final com.myxhs.cart.rpc.ProductSkuRpcClient productSkuRpcClient;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> cartAddScript;
    private final com.myxhs.cart.mapper.CartItemMapper cartItemMapper;  // P2-7: Redis 丢失时从 MySQL 恢复
    private final DefaultRedisScript<Long> cartRemoveScript;
    private final DefaultRedisScript<Long> cartCheckAllScript;
    private final DefaultRedisScript<Long> cartUpdateQuantityScript;
    private final DefaultRedisScript<Long> cartClearScript;
    private final DefaultRedisScript<Long> cartMergeItemScript;
    private final DefaultRedisScript<Long> cartCheckItemScript;

    /** 商品上架状态码（对应 ProductStatus.ON_SHELF） */
    private static final int PRODUCT_STATUS_ON_SHELF = 1;

    /** 购物车商品数量上限（品种数） */
    private static final int MAX_CART_SIZE = 50;

    /** 单品数量上限 */
    private static final int MAX_ITEM_QUANTITY = 99;

    /** 购物车数据 TTL（30 天，C-13: 修复前无过期，违背注释"7天"承诺） */
    private static final long CART_TTL_DAYS = 30;
    private static final java.time.Duration CART_TTL = java.time.Duration.ofDays(CART_TTL_DAYS);

    /**
     * Redis Key 前缀
     * <p>
     * 【修复M1】使用 {userId} 作为 hash tag，保证同一用户的三个 Key（items/checked/sort）
     * 在 Redis Cluster 模式下落到同一个 slot，避免 Lua 脚本报 CROSSSLOT 错误。
     * Key 格式: myxhs:cart:{userId}:items / myxhs:cart:{userId}:checked / myxhs:cart:{userId}:sort
     * </p>
     */
    private static final String KEY_PREFIX = "myxhs:cart:{";
    private static final String ITEMS_KEY_SUFFIX = "}:items";
    private static final String CHECKED_KEY_SUFFIX = "}:checked";
    private static final String SORT_KEY_SUFFIX = "}:sort";

    private static String itemsKey(Long userId) { return KEY_PREFIX + userId + ITEMS_KEY_SUFFIX; }
    private static String checkedKey(Long userId) { return KEY_PREFIX + userId + CHECKED_KEY_SUFFIX; }
    private static String sortKey(Long userId) { return KEY_PREFIX + userId + SORT_KEY_SUFFIX; }
    private static String clearedMarkerKey(Long userId) { return KEY_PREFIX + userId + "}:cleared"; }

    /** MQ Topic */
    private static final String CART_TOPIC = "CART_TOPIC";

    // ==================== 加入购物车 ====================

    /**
     * 加入购物车（Lua 脚本原子操作）
     * <p>
     * 原子性保证：
     * 使用 Lua 脚本将"检查上限 + HINCRBY + 截断 + SADD + ZADD"合并为一次原子操作。
     * 对比之前的非原子方案（先 HLEN 检查再 HINCRBY），Lua 脚本彻底解决并发超限问题。
     * </p>
     * <p>
     * 为什么购物车也要用 Lua？（对标社交服务 follow_self.lua / follow_target.lua）
     * - 社交服务：ZADD 关注 + INCR 关注数  → 2+2 命令（拆分为 self/target）
     * - 购物车：HEXISTS + HLEN + HINCRBY + SADD + ZADD → 5 命令原子
     * - 核心诉求相同：多个 Redis 命令必须原子执行，防止中间状态
     * </p>
     */
    public void addToCart(Long userId, CartAddRequest request) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String sortKey = sortKey(userId);

        // G3-02-15（2026-08-16）：加购前校验 SKU 存在性——修复幽灵购物车条目
        // （此前无校验：不存在的 skuId 加购 200 成功 + 落 MySQL，列表层仅靠 valid 标记兜底）
        // 对照 T-103/116（like/favorite 校验目标存在性）；商品服务不可用时降级放行（写操作可用性优先）
        if (!skuExists(request.getSkuId())) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND, "商品不存在或未上架");
        }

        // Lua 脚本原子执行：检查上限 + 累加 + 截断 + 选中 + 排序
        Long result = stringRedisTemplate.execute(
                cartAddScript,
                List.of(itemsKey, checkedKey, sortKey),
                String.valueOf(request.getSkuId()),
                String.valueOf(request.getQuantity()),
                String.valueOf(MAX_CART_SIZE),
                String.valueOf(MAX_ITEM_QUANTITY),
                String.valueOf(System.currentTimeMillis())
        );

        if (result == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "购物车操作失败");
        }

        if (result == -1) {
            throw new BizException(ResultCode.CART_LIMIT_EXCEEDED,
                    "购物车最多添加" + MAX_CART_SIZE + "种商品");
        }

        log.info("[购物车] 加入成功: userId={}, skuId={}, quantity={}, 当前数量={}",
                userId, request.getSkuId(), request.getQuantity(), result);

        clearClearedMarker(userId);
        // MQ 异步持久化（Lua 脚本外执行，MQ 失败不影响购物车操作）
        refreshTTL(userId);
        // T-107（2026-08-15）：Lua 返回 >=10000 表示新商品（checked=1 默认勾选，与 Redis SADD 一致）；
        // 已存在商品返回 <10000 → checked=null（不覆盖 MySQL 勾选态，消除 Redis/MySQL 不一致源头）
        if (result >= 10000) {
            int actualQty = result.intValue() - 10000;
            sendCartSyncEvent(userId, request.getSkuId(), actualQty, 1, "ADD");
        } else {
            sendCartSyncEvent(userId, request.getSkuId(), result.intValue(), null, "ADD");
        }
    }

    // ==================== 修改数量 ====================

    /**
     * 修改购物车商品数量（C-02 修复：Lua 原子化 HEXISTS + HSET）
     * <p>
     * 修复前：hasKey + HSET 两步操作非原子，并发删除后 HSET 会使已删商品"复活"。
     * 修复后：Lua 脚本内 HEXISTS 先校验，若商品已被并发删除则返回 0 抛异常。
     * </p>
     */
    public void updateQuantity(Long userId, CartUpdateQuantityRequest request) {
        String itemsKey = itemsKey(userId);
        String skuIdStr = String.valueOf(request.getSkuId());

        // Lua 原子检查+设置：HEXISTS 先校验，避免并发删除后复活
        Long result = stringRedisTemplate.execute(
                cartUpdateQuantityScript,
                List.of(itemsKey),
                skuIdStr, String.valueOf(request.getQuantity()));

        if (result == null || result == 0) {
            throw new BizException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        log.info("[购物车] 修改数量: userId={}, skuId={}, newQuantity={}",
                userId, request.getSkuId(), request.getQuantity());

        clearClearedMarker(userId);
        refreshTTL(userId);
        sendCartSyncEvent(userId, request.getSkuId(), request.getQuantity(), null, "UPDATE");
    }

    // ==================== 删除商品 ====================

    /**
     * 从购物车删除商品（Lua 脚本原子操作）
     * <p>
     * 原子性保证：
     * 三个结构（Hash + Set + ZSet）必须同时删除，否则会出现：
     * - Hash 删了但 Set 还有 → 勾选了一个不存在的商品
     * - Hash 删了但 ZSet 还有 → 排序列表中出现幽灵商品
     * Lua 脚本保证三结构原子删除，不会出现中间状态。
     * </p>
     */
    public void removeFromCart(Long userId, Long skuId) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String sortKey = sortKey(userId);

        // Lua 脚本原子删除三结构
        Long result = stringRedisTemplate.execute(
                cartRemoveScript,
                List.of(itemsKey, checkedKey, sortKey),
                String.valueOf(skuId)
        );

        log.info("[购物车] 删除商品: userId={}, skuId={}, result={}",
                userId, skuId, result);

        // MQ 异步持久化
        refreshTTL(userId);
        sendCartSyncEvent(userId, skuId, 0, 0, "DELETE");
    }

    // ==================== 勾选/取消勾选 ====================

    /**
     * 勾选/取消勾选单个商品（Lua 脚本原子操作）
     * <p>
     * 修复前：hasKey + SADD/SREM 非原子序列 — 并发 removeFromCart 在 hasKey 和 SADD
     * 之间删除商品后，SADD 会在 checked Set 中创建幽灵条目（Check Set 有但 Items Hash 无）。
     * 修复后：Lua 脚本内 HEXISTS + SADD/SREM 原子化，彻底消除 TOCTOU 窗口。
     * </p>
     */
    public void checkItem(Long userId, CartCheckRequest request) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String skuIdStr = String.valueOf(request.getSkuId());

        // Lua 原子校验 + 操作：HEXISTS 验证 → SADD/SREM（单次原子执行）
        Long result = stringRedisTemplate.execute(
                cartCheckItemScript,
                List.of(itemsKey, checkedKey),
                skuIdStr, request.getChecked() ? "1" : "0");

        if (result == null || result == 0) {
            throw new BizException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        log.info("[购物车] 勾选变更: userId={}, skuId={}, checked={}",
                userId, request.getSkuId(), request.getChecked());

        clearClearedMarker(userId);
        // MQ 异步持久化
        refreshTTL(userId);
        sendCartSyncEvent(userId, request.getSkuId(), null,
                request.getChecked() ? 1 : 0, "CHECK");
    }

    // ==================== 全选/取消全选 ====================

    /**
     * 全选/取消全选（Lua 脚本原子操作）
     * <p>
     * 修复前：非原子的 KEYS → DEL → SADD 三步操作，并发 addToCart 可能丢失新商品的选中状态。
     * 修复后：Lua 脚本在 Redis 单线程中原子执行 HKEYS + DEL + SADD，彻底消除竞态窗口。
     * </p>
     * <p>
     * 取消全选：直接 DEL 整个 checked Set（O(1) 操作，比逐个 SREM 高效）
     * </p>
     */
    public void checkAll(Long userId, boolean checked) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);

        Long result = stringRedisTemplate.execute(
                cartCheckAllScript,
                List.of(itemsKey, checkedKey),
                checked ? "1" : "0"
        );

        log.info("[购物车] 全选变更: userId={}, checked={}, selectedCount={}",
                userId, checked, result != null ? result : 0);

        clearClearedMarker(userId);
        // C-11: 发送 CHECK_ALL 事件同步勾选状态到 MySQL
        refreshTTL(userId);
        sendCartSyncEvent(userId, null, null, checked ? 1 : 0, "CHECK_ALL");
    }

    // ==================== 购物车列表 ====================

    /**
     * 获取购物车列表
     * <p>
     * 流程：
     * 1. Pipeline 一次网络往返获取 Redis 三结构数据（优化：减少 3 次 RTT 为 1 次）
     * 2. Feign 调 Product 服务获取 SKU 详情（降级：商品服务不可用时标记失效）
     * 3. 组装 CartItemVO（含失效标记 + 价格计算）
     * 4. 按加购时间倒序排列（最新加购在前）
     * </p>
     * <p>
     * 性能优化对比：
     * - 优化前：3 次 Redis 命令 + N 次 Feign 调用 = 3 + N 次网络往返
     * - 优化后：1 次 Pipeline + 1 次批量 Feign = 2 次网络往返（后续 Product 补充批量接口后）
     * </p>
     */
    public CartListVO getCartList(Long userId) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String sortKey = sortKey(userId);

        // 1. Pipeline 一次获取三结构数据（减少 3 次 RTT 为 1 次）
        List<Object> pipelineResults = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    byte[] itemsKeyBytes = itemsKey.getBytes();
                    byte[] checkedKeyBytes = checkedKey.getBytes();
                    byte[] sortKeyBytes = sortKey.getBytes();

                    connection.hashCommands().hGetAll(itemsKeyBytes);
                    connection.setCommands().sMembers(checkedKeyBytes);
                    connection.zSetCommands().zRevRangeWithScores(sortKeyBytes, 0, -1);
                    return null;
                }
        );

        // 2. 解析 Pipeline 结果
        @SuppressWarnings("unchecked")
        Map<Object, Object> itemsMap = pipelineResults.get(0) != null
                ? (Map<Object, Object>) pipelineResults.get(0)
                : Collections.emptyMap();

        if (itemsMap.isEmpty()) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(clearedMarkerKey(userId)))) {
                return CartListVO.builder()
                        .items(Collections.emptyList())
                        .checkedCount(0)
                        .checkedAmount(BigDecimal.ZERO)
                        .totalCount(0)
                        .allChecked(false)
                        .build();
            }
            // P2-7: Redis 购物车丢失（主从切换/重启/驱逐）时从 MySQL 恢复并回写 Redis，
            //       与 CartReconcileJob 注释"Redis 丢失后以 MySQL 恢复"保持一致
            itemsMap = restoreCartFromDb(userId, itemsKey, checkedKey, sortKey);
            if (itemsMap.isEmpty()) {
                return CartListVO.builder()
                        .items(Collections.emptyList())
                        .checkedCount(0)
                        .checkedAmount(BigDecimal.ZERO)
                        .totalCount(0)
                        .allChecked(false)  // C-24: 空购物车不应显示"全选"
                        .build();
            }
            // O-P2-7-1（2026-08-16）：恢复后重新读取三结构——
            // 修复前 checkedSet/sortMap 仍用恢复前的空 pipeline 结果，导致恢复后首次响应
            // checked=False/checkedCount=0（第二次起才正确）；restore 已回写 Redis，重读即可
            pipelineResults = stringRedisTemplate.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                        byte[] itemsKeyBytes = itemsKey.getBytes();
                        byte[] checkedKeyBytes = checkedKey.getBytes();
                        byte[] sortKeyBytes = sortKey.getBytes();
                        connection.hashCommands().hGetAll(itemsKeyBytes);
                        connection.setCommands().sMembers(checkedKeyBytes);
                        connection.zSetCommands().zRevRangeWithScores(sortKeyBytes, 0, -1);
                        return null;
                    }
            );
        }

        @SuppressWarnings("unchecked")
        Set<Object> checkedRawSet = pipelineResults.get(1) != null
                ? (Set<Object>) pipelineResults.get(1)
                : Collections.emptySet();
        Set<String> checkedSet = checkedRawSet.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());

        @SuppressWarnings("unchecked")
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> sortTuples =
                pipelineResults.get(2) != null
                        ? (Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>) pipelineResults.get(2)
                        : Collections.emptySet();
        Map<String, Double> sortMap = new HashMap<>();
        if (sortTuples != null) {
            for (var tuple : sortTuples) {
                if (tuple.getValue() != null && tuple.getScore() != null) {
                    sortMap.put(tuple.getValue(), tuple.getScore());
                }
            }
        }

        // 3. 批量查询 SKU 详情（Feign 调 Product 服务）；脏数据 key 防御性跳过
        List<Long> skuIds = new ArrayList<>();
        for (Object k : itemsMap.keySet()) {
            try {
                skuIds.add(Long.parseLong(k.toString()));
            } catch (NumberFormatException e) {
                log.warn("[购物车] 跳过脏数据key(skuId非数字): userId={}, key={}", userId, k);
            }
        }
        Map<Long, ProductFeignClient.SkuDTO> skuMap = batchGetSkuInfo(skuIds);

        // 4. 组装 CartItemVO
        List<CartItemVO> cartItems = new ArrayList<>();
        int checkedCount = 0;
        BigDecimal checkedAmount = BigDecimal.ZERO;

        for (Map.Entry<Object, Object> entry : itemsMap.entrySet()) {
            String skuIdStr = entry.getKey().toString();
            int quantity;
            Long skuId;
            try {
                quantity = Integer.parseInt(entry.getValue().toString());
                skuId = Long.parseLong(skuIdStr);
            } catch (NumberFormatException e) {
                log.warn("[购物车] 跳过脏数据(数量/skuId非数字): userId={}, key={}, value={}",
                        userId, skuIdStr, entry.getValue());
                continue;
            }
            boolean isChecked = checkedSet.contains(skuIdStr);

            ProductFeignClient.SkuDTO sku = skuMap.get(skuId);

            CartItemVO.CartItemVOBuilder builder = CartItemVO.builder()
                    .skuId(skuId)
                    .quantity(quantity)
                    .checked(isChecked)
                    .addedAt(sortMap.getOrDefault(skuIdStr, 0D).longValue());

            boolean valid = false;
            if (sku != null) {
                builder.spuId(sku.getSpuId())
                        .name(sku.getName())
                        .price(sku.getPrice())
                        .originalPrice(sku.getOriginalPrice())
                        .specs(sku.getSpecs());

                // 判断商品是否有效
                // 注：product 批量接口已过滤 SKU status=ON_SHELF，下架 SKU 不会返回此处分支。
                // 保留此检查作为防御层——若 product 侧行为变更，cart 仍能正确处理。
                // 注2：不再检查 sku.getStock()——product 的 stock 是创建时冗余占位值（从不更新），
                // 真实库存校验由 inventory 服务在下单/扣减时执行。
                // T-047：SPU 下架（spuStatus=0）→ 商品无效（SPU 维度），与 SKU 下架同语义
                if (sku.getStatus() == null || sku.getStatus() != PRODUCT_STATUS_ON_SHELF
                        || sku.getSpuStatus() == null || sku.getSpuStatus() != PRODUCT_STATUS_ON_SHELF) {
                    builder.valid(false).invalidReason("商品已下架");
                } else {
                    builder.valid(true);
                    valid = true;
                }

                // 计算选中商品金额（仅有效且选中的商品参与计算）
                if (isChecked && valid && sku.getPrice() != null) {
                    checkedCount += quantity;
                    checkedAmount = checkedAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(quantity)));
                }
            } else {
                // SKU 信息获取失败（商品服务不可用或商品已删除）
                builder.valid(false).invalidReason("商品信息获取失败");
            }

            cartItems.add(builder.build());
        }

        // 5. 按加购时间倒序排列（最新加购在前）
        cartItems.sort((a, b) -> Long.compare(
                b.getAddedAt() != null ? b.getAddedAt() : 0,
                a.getAddedAt() != null ? a.getAddedAt() : 0));

        // 6. 判断是否全选（只看有效商品）
        long validCount = cartItems.stream().filter(i -> Boolean.TRUE.equals(i.getValid())).count();
        long validCheckedCount = cartItems.stream()
                .filter(i -> Boolean.TRUE.equals(i.getValid()) && Boolean.TRUE.equals(i.getChecked()))
                .count();
        boolean allChecked = validCount > 0 && validCheckedCount == validCount;

        return CartListVO.builder()
                .items(cartItems)
                .checkedCount(checkedCount)
                .checkedAmount(checkedAmount)
                .totalCount(itemsMap.size())
                .allChecked(allChecked)
                .build();
    }

    // ==================== 匿名购物车合并 ====================

    /**
     * 匿名购物车合并（登录时调用）
     * <p>
     * 合并策略：
     * - 同一 SKU：取较大数量（但不超过 99）
     * - 新 SKU：直接加入（但总数不超过 50）
     * - 合并后匿名购物车由前端清除（C-13: 30 天无操作自动过期）
     * </p>
     * <p>
     * 幂等性保证：
     * 合并操作是幂等的——多次合并结果一致，因为取的是 max 值。
     * 即使网络超时导致客户端重试，也不会产生脏数据。
     * </p>
     */
    public void mergeAnonymousCart(Long userId, CartMergeRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return;
        }

        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String sortKey = sortKey(userId);

        for (CartMergeRequest.MergeItem item : request.getItems()) {
            if (item.getSkuId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }

            // G3-02-15（2026-08-16）：合并同样校验 SKU 存在性（幽灵条目不入购物车）
            if (!skuExists(item.getSkuId())) {
                log.warn("[购物车] 合并跳过（SKU不存在）: userId={}, skuId={}", userId, item.getSkuId());
                continue;
            }

            String skuIdStr = String.valueOf(item.getSkuId());

            // 统一 Lua 脚本原子处理：HEXISTS→已有项 HGET+max()+HSET / 新项 HLEN+HSET+SADD+ZADD NX
            // 替代原 hasKey→get→put 非原子路径：并发删除后 put 会复活已删商品
            Long mergeResult = stringRedisTemplate.execute(
                    cartMergeItemScript,
                    List.of(itemsKey, checkedKey, sortKey),
                    skuIdStr, String.valueOf(item.getQuantity()),
                    String.valueOf(MAX_CART_SIZE),
                    String.valueOf(MAX_ITEM_QUANTITY),
                    String.valueOf(System.currentTimeMillis()));

            if (mergeResult == null || Long.valueOf(0).equals(mergeResult)) {
                log.warn("[购物车] 合并跳过（已满或上限触发）: userId={}, skuId={}", userId, item.getSkuId());
                continue;
            }

            // Lua 返回值 >= 10000 表示已有商品（实际数量 = 返回值 - 10000），
            // 已有商品 Lua 不触碰 checked Set，因此发 UPDATE 事件（checked=null 不改 MySQL 勾选状态），
            // 避免统一发 ADD+checked=1 强制覆盖 MySQL 勾选状态与 Redis 不一致
            if (mergeResult >= 10000) {
                int actualQty = (int) (mergeResult - 10000);
                sendCartSyncEvent(userId, item.getSkuId(), actualQty, null, "UPDATE");
            } else {
                int actualQty = mergeResult.intValue();
                // 新商品：Lua 已 SADD 到 checked Set（默认勾选），发 ADD 事件对齐
                sendCartSyncEvent(userId, item.getSkuId(), actualQty, 1, "ADD");
            }
        }

        clearClearedMarker(userId);
        // 合并写入后刷新 TTL（与其他写路径一致，防止长期活跃用户购物车过期）
        refreshTTL(userId);
        log.info("[购物车] 匿名购物车合并完成: userId={}, 合并{}项", userId, request.getItems().size());
    }

    // ==================== 获取购物车商品数量 ====================

    /**
     * 获取购物车商品品种数（用于角标显示）
     */
    public int getCartCount(Long userId) {
        String itemsKey = itemsKey(userId);
        Long size = stringRedisTemplate.opsForHash().size(itemsKey);
        return size != null ? size.intValue() : 0;
    }

    // ==================== 清空购物车 ====================

    /**
     * 清空购物车
     * <p>
     * 删除 Redis 中用户的三个购物车结构（items/checked/sort），
     * 并发送 MQ 事件用于异步持久化（标记全部删除）。
     * </p>
     */
    public void clearCart(Long userId) {
        String itemsKey = itemsKey(userId);
        String checkedKey = checkedKey(userId);
        String sortKey = sortKey(userId);

        // RV30：三结构删除 + 清空标记写入收敛到单个 Lua，避免并发加购在两步之间写回被覆盖
        stringRedisTemplate.execute(
                cartClearScript,
                List.of(itemsKey, checkedKey, sortKey, clearedMarkerKey(userId)),
                String.valueOf(CART_TTL.getSeconds()));

        log.info("[购物车] 清空成功: userId={}", userId);

        // MQ 异步通知清空
        sendCartSyncEvent(userId, null, 0, 0, "CLEAR");
    }

    // ==================== 私有方法 ====================

    /**
     * 批量获取 SKU 信息
     * <p>
     * 当前 Product 服务未提供批量接口，通过循环单查实现。
     * 后续优化：Product 服务补充批量接口后切换到一次网络往返。
     * </p>
     * <p>
     * 降级策略：
     * - 单个 SKU 查询失败不影响其他商品展示
     * - 商品服务完全不可用时，购物车仍可展示（标记"商品信息获取失败"）
     * - 加购/删购/改数量等写操作不依赖商品服务
     * </p>
     */
    /**
     * G3-02-15（2026-08-16）：SKU 存在性校验（加购/合并前置）
     * <p>
     * product getSkuDetail 不过滤 status（下架 SKU 详情仍返回 200）——本校验只拦截"不存在"的幽灵 SKU；
     * 商品服务不可用时降级放行（写操作不依赖商品服务，可用性优先，列表层由 valid 标记兜底）。
     * </p>
     */
    private boolean skuExists(Long skuId) {
        try {
            R<ProductFeignClient.SkuDTO> response = productSkuRpcClient.getSkuDetail(skuId);
            if (response == null || !response.isSuccess() || response.getData() == null || response.getData().getId() == null) {
                return false;
            }
            ProductFeignClient.SkuDTO sku = response.getData();
            return sku.getStatus() != null && sku.getStatus() == PRODUCT_STATUS_ON_SHELF;
        } catch (Exception e) {
            log.warn("[购物车] SKU存在性校验失败, 降级放行: skuId={}, error={}", skuId, e.getMessage());
            return true;
        }
    }

    private Map<Long, ProductFeignClient.SkuDTO> batchGetSkuInfo(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new HashMap<>();
        }

        // 一次批量调用替代 N 次循环单查
        try {
            R<List<ProductFeignClient.SkuDTO>> response = productSkuRpcClient.batchGetSkuDetails(skuIds);
            if (response != null && response.isSuccess() && response.getData() != null) {
                Map<Long, ProductFeignClient.SkuDTO> result = new HashMap<>();
                for (ProductFeignClient.SkuDTO sku : response.getData()) {
                    if (sku.getId() != null) {
                        result.put(sku.getId(), sku);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[购物车] 批量获取SKU信息失败, 降级跳过, skuIds={}, error={}", skuIds, e.getMessage());
        }

        return new HashMap<>();
    }

    /**
     * C-13: 刷新购物车三结构的 TTL
     * 每次写操作后延长过期时间，30 天无操作自动清除。
     */
    private void refreshTTL(Long userId) {
        try {
            Boolean itemsOk = stringRedisTemplate.expire(itemsKey(userId), CART_TTL);
            Boolean checkedOk = stringRedisTemplate.expire(checkedKey(userId), CART_TTL);
            Boolean sortOk = stringRedisTemplate.expire(sortKey(userId), CART_TTL);
            if (!Boolean.TRUE.equals(itemsOk) || !Boolean.TRUE.equals(checkedOk) || !Boolean.TRUE.equals(sortOk)) {
                log.warn("[购物车] TTL刷新部分失败: userId={}, itemsOk={}, checkedOk={}, sortOk={}",
                        userId, itemsOk, checkedOk, sortOk);
            }
        } catch (Exception e) {
            log.error("[购物车] TTL刷新异常: userId={}", userId, e);
        }
    }

    private void clearClearedMarker(Long userId) {
        try {
            stringRedisTemplate.delete(clearedMarkerKey(userId));
        } catch (Exception e) {
            log.warn("[购物车] 清理清空标记失败: userId={}", userId, e);
        }
    }

    /**
     * 发送购物车同步事件到 MQ（异步持久化到 MySQL）
     * <p>
     * 设计决策：MQ 发送失败不影响购物车操作
     * - Redis 是权威数据源，MQ 只是异步落库的通道
     * - 即使 MQ 全部丢失，购物车功能不受影响
     * - 后续通过对账修复机制保证 Redis ↔ MySQL 最终一致
     * </p>
     */
    private void sendCartSyncEvent(Long userId, Long skuId, Integer quantity, Integer checked, String action) {
        try {
            CartSyncEvent event = CartSyncEvent.builder()
                    .userId(userId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .checked(checked)
                    .action(action)
                    .build();
            // 用单调递增序列号保证同毫秒事件不被 C-05 误判跳过
            java.time.Instant eventTs = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
                    .plusNanos(EVENT_SEQ.incrementAndGet());
            event.setTimestamp(eventTs);
            if ("CLEAR".equals(action)) {
                event.setClearBarrierTs(eventTs.toEpochMilli());
            }

            String payload = objectMapper.writeValueAsString(event);
            rocketMQTemplate.asyncSend(
                    CART_TOPIC + ":" + action,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("[购物车] MQ发送成功: action={}, userId={}, skuId={}",
                                    action, userId, skuId);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("[购物车] MQ发送失败(不影响购物车操作): action={}, userId={}, skuId={}",
                                    action, userId, skuId, e);
                        }
                    }
            );
        } catch (Exception e) {
            // MQ 发送失败不影响购物车操作（Redis 为权威数据源）
            log.error("[购物车] MQ发送异常: userId={}, skuId={}, action={}", userId, skuId, action, e);
        }
    }


    /**
     * P2-7: 从 MySQL 恢复购物车到 Redis（items/checked/sort 三结构），返回 itemsMap 供读取
     */
    private Map<Object, Object> restoreCartFromDb(Long userId, String itemsKey, String checkedKey, String sortKey) {
        List<com.myxhs.cart.entity.CartItem> dbItems = cartItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.myxhs.cart.entity.CartItem>()
                        .eq(com.myxhs.cart.entity.CartItem::getUserId, userId)
                        // G3（2026-08-16）：恢复上限防御——最多恢复 MAX_CART_SIZE 种（最新在前），
                        // 与加购 Lua 的 50 种上限一致，防止历史脏数据恢复后购物车超限
                        .orderByDesc(com.myxhs.cart.entity.CartItem::getCreatedAt)
                        .last("LIMIT " + MAX_CART_SIZE));
        if (dbItems.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Object, Object> itemsMap = new LinkedHashMap<>();
        stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (com.myxhs.cart.entity.CartItem item : dbItems) {
                byte[] skuBytes = String.valueOf(item.getSkuId()).getBytes();
                connection.hashCommands().hSet(itemsKey.getBytes(), skuBytes,
                        String.valueOf(item.getQuantity()).getBytes());
                if (item.getChecked() != null && item.getChecked() == 1) {
                    connection.setCommands().sAdd(checkedKey.getBytes(), skuBytes);
                }
                connection.zSetCommands().zAdd(sortKey.getBytes(),
                        item.getCreatedAt() != null ? item.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : System.currentTimeMillis(),
                        skuBytes);
                itemsMap.put(String.valueOf(item.getSkuId()), String.valueOf(item.getQuantity()));
            }
            return null;
        });
        log.info("[购物车] P2-7: Redis 丢失，已从 MySQL 恢复: userId={}, items={}", userId, dbItems.size());
        // T-108（2026-08-15）：恢复后补刷新三 key TTL（30 天）——修复恢复后永不过期的内存残留
        refreshTTL(userId);
        return itemsMap;
    }
}
