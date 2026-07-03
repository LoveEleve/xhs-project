package com.myxhs.product.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.R;
import com.myxhs.product.dto.request.SkuCreateRequest;
import com.myxhs.product.dto.request.SpuCreateRequest;
import com.myxhs.product.dto.request.SpuUpdateRequest;
import com.myxhs.product.dto.response.*;
import com.myxhs.product.service.CategoryService;
import com.myxhs.product.service.SkuService;
import com.myxhs.product.service.SpuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商品接口
 * <p>
 * 提供 SPU/SKU 的 CRUD、上架/下架、分类树查询等功能。
 * 商品详情查询走多级缓存（Caffeine → Redis → MySQL），无需登录。
 * 写操作需要登录（通过 X-User-Id Header）。
 * </p>
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final SpuService spuService;
    private final SkuService skuService;
    private final CategoryService categoryService;

    // ==================== SPU 接口 ====================

    /**
     * 创建 SPU
     */
    @PostMapping("/spu")
    @RateLimit(prefix = "product:create", maxRequests = 10, windowSeconds = 60)
    public R<Map<String, Long>> createSpu(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody SpuCreateRequest request) {
        Long spuId = spuService.createSpu(request);
        return R.ok("创建成功", Map.of("spuId", spuId));
    }

    /**
     * 更新 SPU
     */
    @PutMapping("/spu/{spuId}")
    public R<Void> updateSpu(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long spuId,
            @RequestBody @Valid SpuUpdateRequest request) {
        spuService.updateSpu(spuId, request);
        return R.ok();
    }

    /**
     * SPU 详情（公开接口，走多级缓存）
     */
    @GetMapping("/spu/{spuId}")
    public R<SpuDetailVO> getSpuDetail(@PathVariable Long spuId) {
        SpuDetailVO detail = spuService.getSpuDetail(spuId);
        if (detail == null) {
            return R.fail(com.myxhs.common.response.ResultCode.PRODUCT_NOT_FOUND);
        }
        return R.ok(detail);
    }

    /**
     * SPU 列表（分页，公开接口）
     */
    @GetMapping("/spu/list")
    public R<PageResult<SpuItemVO>> listSpus(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long categoryId) {
        return R.ok(spuService.listSpus(pageNum, pageSize, categoryId));
    }

    /**
     * 上架/下架 SPU
     */
    @PutMapping("/spu/{spuId}/status")
    public R<Void> updateSpuStatus(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long spuId,
            @RequestParam Integer status) {
        spuService.updateSpuStatus(spuId, status);
        return R.ok();
    }

    // ==================== SKU 接口 ====================

    /**
     * 创建 SKU
     */
    @PostMapping("/sku")
    public R<Map<String, Long>> createSku(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody SkuCreateRequest request) {
        Long skuId = skuService.createSku(request);
        return R.ok("创建成功", Map.of("skuId", skuId));
    }

    /**
     * SKU 详情
     */
    @GetMapping("/sku/{skuId}")
    public R<SkuVO> getSkuDetail(@PathVariable Long skuId) {
        return R.ok(skuService.getSkuDetail(skuId));
    }

    /**
     * 批量获取 SKU 详情（内部接口，供购物车等服务调用）
     */
    @GetMapping("/sku/batch")
    public R<List<SkuVO>> batchGetSkuDetails(@RequestParam("skuIds") List<Long> skuIds) {
        return R.ok(skuService.batchGetSkuDetails(skuIds));
    }

    /**
     * 按 SPU 查 SKU 列表
     */
    @GetMapping("/sku/list/{spuId}")
    public R<List<SkuVO>> listSkusBySpuId(@PathVariable Long spuId) {
        return R.ok(skuService.listSkusBySpuId(spuId));
    }

    // ==================== 分类接口 ====================

    /**
     * 三级分类树（公开接口，缓存 1 小时）
     */
    @GetMapping("/category/tree")
    public R<List<CategoryTreeVO>> getCategoryTree() {
        return R.ok(categoryService.getCategoryTree());
    }
}
