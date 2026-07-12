package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.NoteCardVO;
import com.myxhs.home.dto.ProductDetailAggVO;
import com.myxhs.home.feign.CounterFeignClient;
import com.myxhs.home.feign.InventoryFeignClient;
import com.myxhs.home.feign.ProductFeignClient;
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
 * 商品详情聚合服务
 * <p>
 * 核心职责：将商品详情页所需的多源数据并行聚合为一个完整的 VO。
 * <p>
 * 聚合编排（2 层并行）：
 * 第 1 层（并行）：SPU 详情 + 商品计数
 * 第 2 层（依赖第 1 层的 SKU 列表）：各 SKU 库存
 * <p>
 * 降级策略：
 * - 商品服务不可用 → 返回 null（商品不存在）
 * - 库存服务不可用 → SKU 库存显示"暂无数据"
 * - 计数服务不可用 → 计数显示 0
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAggService {

    private final ProductFeignClient productFeignClient;
    private final InventoryFeignClient inventoryFeignClient;
    private final CounterFeignClient counterFeignClient;
    private final ExecutorService aggregatorPool;
    private final ExecutorService batchFeignPool;

    /**
     * 聚合商品详情
     *
     * @param spuId 商品 SPU ID
     */
    @SuppressWarnings("unchecked")
    public ProductDetailAggVO getProductDetail(Long spuId) {

        // 全局请求级超时控制：整个聚合不超过 4 秒
        long startTime = System.nanoTime();
        long globalTimeoutMs = 4000;

        // ========== 第 1 层并行：SPU 详情 + 商品计数 ==========

        // 1a. SPU 详情（含 SKU 列表）
        CompletableFuture<Map<String, Object>> spuFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        R<Map<String, Object>> r = productFeignClient.getSpuDetail(spuId);
                        return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                    } catch (Exception e) {
                        log.warn("[商品详情] 获取SPU异常: spuId={}", spuId, e);
                        return Collections.emptyMap();
                    }
                }, aggregatorPool);

        // 1b. 商品计数（收藏数、浏览数）
        CompletableFuture<Map<String, Long>> counterFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Map<String, Object> query = new HashMap<>();
                        query.put("targetType", 2); // 2=商品
                        query.put("targetId", spuId);
                        query.put("countTypes", List.of(2, 4)); // 2=收藏 4=浏览
                        Map<String, Object> request = Map.of("queries", List.of(query));
                        R<Map<String, Map<String, Long>>> r = counterFeignClient.batchGetCounts(request);
                        if (r != null && r.isSuccess() && r.getData() != null) {
                            String key = "2:" + spuId;
                            return r.getData().getOrDefault(key, Collections.emptyMap());
                        }
                    } catch (Exception e) {
                        log.warn("[商品详情] 获取计数失败: spuId={}", spuId);
                    }
                    return Collections.<String, Long>emptyMap();
                }, aggregatorPool);

        // 等待第 1 层完成
        try {
            CompletableFuture.allOf(spuFuture, counterFuture).get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[商品详情] 第1层聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[商品详情] 第1层聚合异常", e);
        }

        Map<String, Object> spuData = spuFuture.getNow(Collections.emptyMap());
        if (spuData.isEmpty()) {
            return null; // 商品不存在
        }

        Map<String, Long> counters = counterFuture.getNow(Collections.emptyMap());

        // 提取 SKU 列表
        List<Map<String, Object>> skuListRaw = Collections.emptyList();
        if (spuData.get("skuList") instanceof List) {
            skuListRaw = (List<Map<String, Object>>) spuData.get("skuList");
        }

        // ========== 第 2 层并行：各 SKU 库存查询（动态超时） ==========
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        long layer2TimeoutMs = Math.max(500, globalTimeoutMs - elapsedMs);
        List<ProductDetailAggVO.SkuWithStockVO> skuWithStockList = aggregateSkuStock(skuListRaw, layer2TimeoutMs);

        // ========== 组装 VO ==========
        return ProductDetailAggVO.builder()
                .spuId(spuId)
                .name((String) spuData.get("name"))
                .description((String) spuData.get("description"))
                .images(spuData.get("images") instanceof List ? (List<String>) spuData.get("images") : Collections.emptyList())
                .categoryId(spuData.get("categoryId") != null ? ((Number) spuData.get("categoryId")).longValue() : null)
                .categoryName((String) spuData.get("categoryName"))
                .status(spuData.get("status") != null ? ((Number) spuData.get("status")).intValue() : null)
                .skuList(skuWithStockList)
                .collectCount(counters.getOrDefault("collect", 0L))
                .viewCount(counters.getOrDefault("view", 0L))
                .relatedNotes(Collections.emptyList()) // 关联笔记暂不聚合，后续可接入搜索服务
                .build();
    }

    /**
     * 并行查询各 SKU 的库存
     *
     * @param skuListRaw SKU 原始数据列表
     * @param timeoutMs  超时时间（毫秒）
     */
    @SuppressWarnings("unchecked")
    private List<ProductDetailAggVO.SkuWithStockVO> aggregateSkuStock(List<Map<String, Object>> skuListRaw, long timeoutMs) {
        if (skuListRaw.isEmpty()) {
            return Collections.emptyList();
        }

        // 并行查询每个 SKU 的库存
        Map<Long, CompletableFuture<Map<String, Object>>> stockFutures = new LinkedHashMap<>();
        for (Map<String, Object> sku : skuListRaw) {
            Long skuId = sku.get("id") != null ? ((Number) sku.get("id")).longValue() : null;
            if (skuId == null) continue;

            CompletableFuture<Map<String, Object>> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            R<Map<String, Object>> r = inventoryFeignClient.getStock(skuId);
                            return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.<String, Object>emptyMap();
                        } catch (Exception e) {
                            log.warn("[商品详情] 查询库存失败: skuId={}", skuId);
                            return Collections.<String, Object>emptyMap();
                        }
                    }, batchFeignPool);
            stockFutures.put(skuId, future);
        }

        // 等待所有库存查询完成（动态超时）
        try {
            CompletableFuture.allOf(stockFutures.values().toArray(new CompletableFuture[0]))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[商品详情] 库存查询超时/异常({}ms)，部分 SKU 库存降级", timeoutMs);
        }

        // 组装 SkuWithStockVO
        List<ProductDetailAggVO.SkuWithStockVO> result = new ArrayList<>();
        for (Map<String, Object> sku : skuListRaw) {
            Long skuId = sku.get("id") != null ? ((Number) sku.get("id")).longValue() : null;
            if (skuId == null) continue;

            Map<String, Object> stockData = stockFutures.containsKey(skuId)
                    ? stockFutures.get(skuId).getNow(Collections.emptyMap())
                    : Collections.emptyMap();

            Integer availableStock = stockData.get("availableStock") != null
                    ? ((Number) stockData.get("availableStock")).intValue() : null;

            BigDecimal price = null;
            if (sku.get("price") != null) {
                price = sku.get("price") instanceof BigDecimal
                        ? (BigDecimal) sku.get("price")
                        : new BigDecimal(sku.get("price").toString());
            }

            result.add(ProductDetailAggVO.SkuWithStockVO.builder()
                    .skuId(skuId)
                    .skuName((String) sku.get("skuName"))
                    .price(price)
                    .image((String) sku.get("image"))
                    .specValues(sku.get("specValues") instanceof Map ? (Map<String, String>) sku.get("specValues") : Collections.emptyMap())
                    .availableStock(availableStock)
                    .hasStock(availableStock != null && availableStock > 0)
                    .build());
        }
        return result;
    }
}
