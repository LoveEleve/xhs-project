package com.myxhs.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.dto.event.CartSyncEvent;
import com.myxhs.cart.dto.request.CartAddRequest;
import com.myxhs.cart.dto.request.CartCheckRequest;
import com.myxhs.cart.dto.request.CartMergeRequest;
import com.myxhs.cart.dto.request.CartUpdateQuantityRequest;
import com.myxhs.cart.dto.response.CartItemVO;
import com.myxhs.cart.dto.response.CartListVO;
import com.myxhs.cart.feign.ProductFeignClient;
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

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ProductFeignClient productFeignClient;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> cartAddScript;
    private final DefaultRedisScript<Long> cartRemoveScript;
    private final DefaultRedisScript<Long> cartCheckAllScript;

    /** 购物车商品数量上限（品种数） */
    private static final int MAX_CART_SIZE = 50;

    /** 单品数量上限 */
    private static final int MAX_ITEM_QUANTITY = 99;

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

        // MQ 异步持久化（Lua 脚本外执行，MQ 失败不影响购物车操作）
        sendCartSyncEvent(userId, request.getSkuId(), result.intValue(), 1, "ADD");
    }

    // ==================== 修改数量 ====================

    /**
     * 修改购物车商品数量
     * <p>
     * 为什么修改数量不用 Lua？
     * 修改数量只涉及 1 个 Redis 命令（HSET），本身就是原子的。
     * 前置的 HEXISTS 检查即使并发也不会产生脏数据（最多多返回一次"商品不存在"）。
     * </p>
     */
    public void updateQuantity(Long userId, CartUpdateQuantityRequest request) {
        String itemsKey = itemsKey(userId);
        String skuIdStr = String.valueOf(request.getSkuId());

        // 校验商品是否在购物车中
        Boolean exists = stringRedisTemplate.opsForHash().hasKey(itemsKey, skuIdStr);
        if (Boolean.FALSE.equals(exists)) {
            throw new BizException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        // 直接设置新数量（HSET 原子操作）
        stringRedisTemplate.opsForHash().put(itemsKey, skuIdStr, String.valueOf(request.getQuantity()));

        log.info("[购物车] 修改数量: userId={}, skuId={}, newQuantity={}",
                userId, request.getSkuId(), request.getQuantity());

        // MQ 异步持久化
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
        sendCartSyncEvent(userId, skuId, 0, 0, "DELETE");
    }

    // ==================== 勾选/取消勾选 ====================

    /**
     * 勾选/取消勾选单个商品
     * <p>
     * 为什么勾选不用 Lua？
     * SADD/SREM 本身是原子操作，且勾选状态不影响购物车核心数据（items Hash）。
     * 即使并发勾选，最终状态也是正确的（幂等操作）。
     * </p>
     */
    public void checkItem(Long userId, CartCheckRequest request) {
        String checkedKey = checkedKey(userId);
        String skuIdStr = String.valueOf(request.getSkuId());

        // 校验商品是否在购物车中
        String itemsKey = itemsKey(userId);
        Boolean exists = stringRedisTemplate.opsForHash().hasKey(itemsKey, skuIdStr);
        if (Boolean.FALSE.equals(exists)) {
            throw new BizException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        if (Boolean.TRUE.equals(request.getChecked())) {
            stringRedisTemplate.opsForSet().add(checkedKey, skuIdStr);
        } else {
            stringRedisTemplate.opsForSet().remove(checkedKey, skuIdStr);
        }

        log.info("[购物车] 勾选变更: userId={}, skuId={}, checked={}",
                userId, request.getSkuId(), request.getChecked());

        // MQ 异步持久化
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
            return CartListVO.builder()
                    .items(Collections.emptyList())
                    .checkedCount(0)
                    .checkedAmount(BigDecimal.ZERO)
                    .totalCount(0)
                    .allChecked(true)
                    .build();
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

        // 3. 批量查询 SKU 详情（Feign 调 Product 服务）
        List<Long> skuIds = itemsMap.keySet().stream()
                .map(k -> Long.parseLong(k.toString()))
                .collect(Collectors.toList());
        Map<Long, ProductFeignClient.SkuDTO> skuMap = batchGetSkuInfo(skuIds);

        // 4. 组装 CartItemVO
        List<CartItemVO> cartItems = new ArrayList<>();
        int checkedCount = 0;
        BigDecimal checkedAmount = BigDecimal.ZERO;

        for (Map.Entry<Object, Object> entry : itemsMap.entrySet()) {
            String skuIdStr = entry.getKey().toString();
            int quantity = Integer.parseInt(entry.getValue().toString());
            Long skuId = Long.parseLong(skuIdStr);
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
                if (sku.getStatus() == null || sku.getStatus() != 1) {
                    builder.valid(false).invalidReason("商品已下架");
                } else if (sku.getStock() != null && sku.getStock() <= 0) {
                    builder.valid(false).invalidReason("库存不足");
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
     * - 合并后匿名购物车由前端清除（或 7 天过期自动清除）
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

        Long currentSize = stringRedisTemplate.opsForHash().size(itemsKey);
        int currentSizeInt = currentSize != null ? currentSize.intValue() : 0;

        for (CartMergeRequest.MergeItem item : request.getItems()) {
            if (item.getSkuId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }

            String skuIdStr = String.valueOf(item.getSkuId());
            Boolean exists = stringRedisTemplate.opsForHash().hasKey(itemsKey, skuIdStr);

            if (Boolean.TRUE.equals(exists)) {
                // 已存在：取较大数量（幂等合并）
                Object currentQtyObj = stringRedisTemplate.opsForHash().get(itemsKey, skuIdStr);
                int currentQty = currentQtyObj != null ? Integer.parseInt(currentQtyObj.toString()) : 0;
                int mergedQty = Math.min(Math.max(currentQty, item.getQuantity()), MAX_ITEM_QUANTITY);
                stringRedisTemplate.opsForHash().put(itemsKey, skuIdStr, String.valueOf(mergedQty));
            } else {
                // 新商品：检查总数上限
                if (currentSizeInt >= MAX_CART_SIZE) {
                    log.warn("[购物车] 合并跳过（已满）: userId={}, skuId={}", userId, item.getSkuId());
                    continue;
                }
                int qty = Math.min(item.getQuantity(), MAX_ITEM_QUANTITY);
                stringRedisTemplate.opsForHash().put(itemsKey, skuIdStr, String.valueOf(qty));
                currentSizeInt++;
            }

            // 默认选中 + 记录排序（NX 语义：已存在的不更新时间）
            stringRedisTemplate.opsForSet().add(checkedKey, skuIdStr);
            stringRedisTemplate.opsForZSet().addIfAbsent(sortKey, skuIdStr, System.currentTimeMillis());
        }

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
    private Map<Long, ProductFeignClient.SkuDTO> batchGetSkuInfo(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new HashMap<>();
        }

        // 一次批量调用替代 N 次循环单查
        try {
            R<List<ProductFeignClient.SkuDTO>> response = productFeignClient.batchGetSkuDetails(skuIds);
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
}
