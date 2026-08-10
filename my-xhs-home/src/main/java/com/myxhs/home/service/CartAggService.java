package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.CartAggVO;
import com.myxhs.home.feign.CartFeignClient;
import com.myxhs.home.feign.CouponFeignClient;
import com.myxhs.home.feign.InventoryFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 购物车聚合服务
 * <p>
 * 核心职责：将购物车页面所需的多源数据并行聚合为一个完整的 VO。
 * <p>
 * 聚合编排（2 层并行）：
 * 第 1 层（并行）：购物车列表 + 可用优惠券
 * 第 2 层（依赖第 1 层的 skuId 列表）：各 SKU 库存 + 商品状态
 * <p>
 * 降级策略：
 * - 购物车服务不可用 → 返回空购物车
 * - 库存服务不可用 → 库存显示"暂无数据"
 * - 优惠券服务不可用 → 可用优惠券数为 0
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartAggService {

    private final CartFeignClient cartFeignClient;
    private final InventoryFeignClient inventoryFeignClient;
    private final CouponFeignClient couponFeignClient;
    private final ExecutorService aggregatorPool;
    private final ExecutorService batchFeignPool;

    /**
     * 聚合购物车数据
     *
     * @param userId 当前登录用户ID
     */
    @SuppressWarnings("unchecked")
    public CartAggVO getCartAgg(Long userId) {

        // ========== 第 1 层并行：购物车列表 + 可用优惠券 ==========

        // 1a. 购物车列表
        CompletableFuture<Map<String, Object>> cartFuture = CompletableFuture
                .supplyAsync(() -> {
                    R<Map<String, Object>> r = cartFeignClient.getCartList(userId);
                    return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                }, aggregatorPool);

        // 1b. 可用优惠券
        CompletableFuture<List<Map<String, Object>>> couponFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        R<List<Map<String, Object>>> r = couponFeignClient.getAvailableCoupons(userId);
                        return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyList();
                    } catch (Exception e) {
                        log.warn("[购物车聚合] 获取可用优惠券失败: userId={}", userId);
                        return Collections.<Map<String, Object>>emptyList();
                    }
                }, aggregatorPool);

        // 等待第 1 层完成
        try {
            CompletableFuture.allOf(cartFuture, couponFuture).get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[购物车聚合] 第1层聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[购物车聚合] 第1层聚合异常", e);
        }

        Map<String, Object> cartData = cartFuture.getNow(Collections.emptyMap());
        List<Map<String, Object>> coupons = couponFuture.getNow(Collections.emptyList());

        // 提取购物车商品列表
        List<Map<String, Object>> cartItems = Collections.emptyList();
        if (cartData.get("items") instanceof List) {
            cartItems = (List<Map<String, Object>>) cartData.get("items");
        }

        if (cartItems.isEmpty()) {
            return CartAggVO.builder()
                    .items(Collections.emptyList())
                    .checkedCount(0)
                    .checkedAmount(BigDecimal.ZERO)
                    .totalCount(0)
                    .allChecked(false)
                    .availableCouponCount(coupons.size())
                    .availableCoupons(coupons)
                    .build();
        }

        // ========== 第 2 层并行：各 SKU 库存查询 ==========
        List<CartAggVO.CartItemAggVO> aggItems = aggregateCartItems(cartItems);

        // 计算汇总信息
        int checkedCount = 0;
        BigDecimal checkedAmount = BigDecimal.ZERO;
        for (CartAggVO.CartItemAggVO item : aggItems) {
            if (Boolean.TRUE.equals(item.getChecked())) {
                checkedCount += item.getQuantity();
                if (item.getTotalAmount() != null) {
                    checkedAmount = checkedAmount.add(item.getTotalAmount());
                }
            }
        }

        boolean allChecked = aggItems.stream().allMatch(i -> Boolean.TRUE.equals(i.getChecked()));

        return CartAggVO.builder()
                .items(aggItems)
                .checkedCount(checkedCount)
                .checkedAmount(checkedAmount)
                .totalCount(aggItems.size())
                .allChecked(allChecked)
                .availableCouponCount(coupons.size())
                .availableCoupons(coupons)
                .build();
    }

    /**
     * 并行查询各购物车商品的库存状态
     */
    @SuppressWarnings("unchecked")
    private List<CartAggVO.CartItemAggVO> aggregateCartItems(List<Map<String, Object>> cartItems) {
        // 并行查询每个 SKU 的库存
        Map<Long, CompletableFuture<Map<String, Object>>> stockFutures = new LinkedHashMap<>();
        for (Map<String, Object> item : cartItems) {
            Long skuId = item.get("skuId") != null ? ((Number) item.get("skuId")).longValue() : null;
            if (skuId == null || stockFutures.containsKey(skuId)) continue;

            CompletableFuture<Map<String, Object>> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            R<Map<String, Object>> r = inventoryFeignClient.getStock(skuId);
                            return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.<String, Object>emptyMap();
                        } catch (Exception e) {
                            log.warn("[购物车聚合] 查询库存失败: skuId={}", skuId);
                            return Collections.<String, Object>emptyMap();
                        }
                    }, batchFeignPool);
            stockFutures.put(skuId, future);
        }

        // 等待所有库存查询完成
        try {
            CompletableFuture.allOf(stockFutures.values().toArray(new CompletableFuture[0]))
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[购物车聚合] 库存查询超时/异常，部分 SKU 库存降级");
        }

        // 组装 CartItemAggVO
        List<CartAggVO.CartItemAggVO> result = new ArrayList<>();
        for (Map<String, Object> item : cartItems) {
            Long skuId = item.get("skuId") != null ? ((Number) item.get("skuId")).longValue() : null;
            if (skuId == null) continue;

            Map<String, Object> stockData = stockFutures.containsKey(skuId)
                    ? stockFutures.get(skuId).getNow(Collections.emptyMap())
                    : Collections.emptyMap();

            Integer availableStock = stockData.get("availableStock") != null
                    ? ((Number) stockData.get("availableStock")).intValue() : null;

            BigDecimal price = parseBigDecimal(item.get("price"));
            Integer quantity = item.get("quantity") != null ? ((Number) item.get("quantity")).intValue() : 0;
            BigDecimal totalAmount = price != null ? price.multiply(BigDecimal.valueOf(quantity)) : null;

            result.add(CartAggVO.CartItemAggVO.builder()
                    .skuId(skuId)
                    .spuId(item.get("spuId") != null ? ((Number) item.get("spuId")).longValue() : null)
                    .skuName((String) item.get("name"))      // C-14: CartItemVO 字段名是 name（修复前用 skuName 永远 null）
                    .skuImage((String) item.get("image"))    // C-14: CartItemVO 字段名是 image（修复前用 skuImage 永远 null）
                    .price(price)
                    .quantity(quantity)
                    .checked(item.get("checked") != null ? (Boolean) item.get("checked") : false)
                    .totalAmount(totalAmount)
                    .availableStock(availableStock)
                    .hasStock(availableStock != null && availableStock >= quantity)
                    .onSale(true) // 默认在售，后续可从商品服务查询
                    .build());
        }
        return result;
    }

    /**
     * 安全解析 BigDecimal
     */
    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
