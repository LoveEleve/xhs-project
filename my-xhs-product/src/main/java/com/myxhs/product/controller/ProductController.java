package com.myxhs.product.controller;

import com.myxhs.common.annotation.Idempotent;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.product.dto.request.SkuCreateRequest;
import com.myxhs.product.dto.request.SpuCreateRequest;
import com.myxhs.product.dto.request.SpuUpdateRequest;
import com.myxhs.product.dto.response.*;
import com.myxhs.product.enums.ProductStatus;
import com.myxhs.product.service.CategoryService;
import com.myxhs.product.service.SkuService;
import com.myxhs.product.service.SpuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商品接口
 * <p>
 * 提供 SPU/SKU 的 CRUD、上架/下架、分类树查询等功能。
 * 商品详情查询走 Redis 逻辑过期缓存 + MySQL 兜底，需 JWT + HMAC 认证（通过 Gateway）。
 * 写操作需要登录（通过 X-User-Id Header）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final SpuService spuService;
    private final SkuService skuService;
    private final CategoryService categoryService;

    /** 管理接口令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token}")
    private String adminToken;

    /** 内部服务调用令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.internal.token}")
    private String internalToken;

    private boolean isAdminCall(String v) { return adminToken != null && !adminToken.isEmpty() && adminToken.equals(v); }
    private boolean isInternalCall(String v) { return internalToken != null && !internalToken.isEmpty() && internalToken.equals(v); }

    // ==================== SPU 接口 ====================

    /**
     * 创建 SPU
     */
    @PostMapping("/spu")
    @Idempotent(key = "'spu:create:' + #request.name + ':' + #request.categoryId", expireSeconds = 10)
    @RateLimit(prefix = "myxhs:product:create", maxRequests = 5, windowSeconds = 60, perUser = true)
    public R<Map<String, Long>> createSpu(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @Valid @RequestBody SpuCreateRequest request) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理员操作");
        }
        // TODO: 权限控制 — t_spu 表当前无 creatorUserId 字段，暂不做所有权校验，仅记录日志
        log.info("[商品] 创建 SPU, userId={}, name={}", userId, request.getName());
        Long spuId = spuService.createSpu(request);
        return R.ok("创建成功", Map.of("spuId", spuId));
    }

    /**
     * 更新 SPU
     */
    @PutMapping("/spu/{spuId}")
    @RateLimit(prefix = "myxhs:product:update", maxRequests = 5, windowSeconds = 60, perUser = true)
    public R<Void> updateSpu(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @PathVariable Long spuId,
            @RequestBody @Valid SpuUpdateRequest request) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理员操作");
        }
        spuService.updateSpu(spuId, request);
        return R.ok();
    }

    /**
     * SPU 详情（单条浏览埋点：记录商品浏览事件）
     */
    @GetMapping("/spu/{spuId}")
    public R<SpuDetailVO> getSpuDetail(
            @PathVariable Long spuId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        SpuDetailVO detail = spuService.getSpuDetail(spuId);
        if (detail == null) {
            return R.fail(com.myxhs.common.response.ResultCode.PRODUCT_NOT_FOUND);
        }
        // 可观测性：商品浏览事件（异步落库，仅单条入口；批量路径不埋点）
        // 仅真实用户请求埋点（gateway 必注入 X-User-Id）；search/home Feign 补全调用（无头）不记录，避免服务调用噪音
        if (userId != null) {
            Long firstSkuId = (detail.getSkuList() != null && !detail.getSkuList().isEmpty())
                    ? detail.getSkuList().get(0).getId() : null;
            spuService.recordSpuViewAsync(spuId, userId, firstSkuId);
        }
        return R.ok(detail);
    }

    /**
     * SPU 列表（分页）
     */
    @GetMapping("/spu/list")
    @RateLimit(prefix = "myxhs:product:list", maxRequests = 60, windowSeconds = 60, perUser = true)
    public R<PageResult<SpuItemVO>> listSpus(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long categoryId) {
        // 限制分页参数范围：pageNum 下限 1（防止负 offset SQL 错误），pageSize 上限 50
        pageNum = Math.max(pageNum, 1);
        pageSize = Math.max(1, Math.min(pageSize, 50));
        return R.ok(spuService.listSpus(pageNum, pageSize, categoryId));
    }

    /**
     * 上架/下架 SPU
     */
    @PutMapping("/spu/{spuId}/status")
    @RateLimit(prefix = "myxhs:product:updateStatus", maxRequests = 5, windowSeconds = 60, perUser = true)
    public R<Void> updateSpuStatus(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @PathVariable Long spuId,
            @RequestParam Integer status) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理员操作");
        }
        // Controller 层校验，避免无效值穿透到 Service 层抛 500
        if (status == null || (status != ProductStatus.ON_SHELF.getCode()
                && status != ProductStatus.OFF_SHELF.getCode())) {
            return R.fail(ResultCode.PARAM_INVALID, "商品状态无效，仅支持 0(下架) 或 1(上架)");
        }
        spuService.updateSpuStatus(spuId, status);
        return R.ok();
    }

    // ==================== SKU 接口 ====================

    /**
     * 创建 SKU
     */
    @PostMapping("/sku")
    @RateLimit(prefix = "myxhs:product:createSku", maxRequests = 5, windowSeconds = 60, perUser = true)
    public R<Map<String, Long>> createSku(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @Valid @RequestBody SkuCreateRequest request) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理员操作");
        }
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
     * 批量获取 SKU 详情（内部接口，供购物车等服务调用，需 X-Internal-Call 校验）
     */
    @GetMapping("/sku/batch")
    public R<List<SkuVO>> batchGetSkuDetails(
            @RequestParam("skuIds") List<Long> skuIds,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        // 限制批量查询数量，防止 WHERE id IN(...) 超大列表打挂 DB
        if (skuIds == null || skuIds.isEmpty() || skuIds.size() > 100) {
            return R.fail(ResultCode.PARAM_INVALID, "skuIds 数量必须在 1-100 之间");
        }
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
     * 三级分类树（缓存 2 小时）
     */
    @GetMapping("/category/tree")
    public R<List<CategoryTreeVO>> getCategoryTree() {
        return R.ok(categoryService.getCategoryTree());
    }
}
