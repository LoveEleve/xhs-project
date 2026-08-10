package com.myxhs.inventory.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.InventoryInitRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.ReinitRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.dto.TccDeductRequest;
import com.myxhs.inventory.dto.response.StockVO;
import com.myxhs.inventory.job.InventoryReconcileJob;
import com.myxhs.inventory.service.InventoryService;
import com.myxhs.inventory.service.InventoryTccService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 库存接口
 * <p>
 * 提供库存初始化、预扣减、确认、释放、查询等接口。
 * 预扣减/确认/释放接口由订单服务内部调用（Feign），不直接暴露给前端。
 * 查询接口可公开访问（商品详情页展示库存）。
 * </p>
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryTccService inventoryTccService;
    private final InventoryReconcileJob inventoryReconcileJob;

    @Value("${inventory.bucket.default-count:2}")
    private int defaultBucketCount;

    /** 管理接口令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token}")
    private String adminToken;

    /** 内部服务调用令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.internal.token}")
    private String internalToken;

    private boolean isInternalCall(String v) { return internalToken != null && !internalToken.isEmpty() && internalToken.equals(v); }
    private boolean isAdminCall(String v) { return adminToken != null && !adminToken.isEmpty() && adminToken.equals(v); }

    /**
     * 库存初始化（DB → Redis 分桶）
     * <p>
     * 管理后台调用，将 SKU 库存初始化到 Redis 分桶中。
     * </p>
     */
    @PostMapping("/init")
    @RateLimit(prefix = "myxhs:inventory:init", maxRequests = 5, windowSeconds = 60)
    public R<Void> initStock(
            @Valid @RequestBody InventoryInitRequest request,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        inventoryService.initStock(request);
        return R.ok();
    }

    /**
     * 预扣减（下单时调用，内部接口，需 X-Internal-Call 校验）
     */
    @PostMapping("/preDeduct")
    public R<Void> preDeduct(
            @Valid @RequestBody PreDeductRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        inventoryService.preDeduct(request);
        return R.ok();
    }

    /**
     * 确认扣减（支付成功后调用，内部接口）
     */
    @PostMapping("/confirm")
    public R<Void> confirmDeduct(
            @Valid @RequestBody ConfirmDeductRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        inventoryService.confirmDeduct(request);
        return R.ok();
    }

    /**
     * 释放库存（取消订单/超时未支付，内部接口）
     */
    @PostMapping("/release")
    public R<Void> releaseStock(
            @Valid @RequestBody ReleaseStockRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        inventoryService.releaseStock(request);
        return R.ok();
    }

    /**
     * 【M9】重新初始化库存（管理后台调用）
     * <p>
     * 清除 Redis Key → 从 MySQL 恢复真实库存 → 按新桶数重新分桶。
     * 用于分桶数调整或 Redis 数据异常修复。
     * </p>
     */
    @PostMapping("/reinit")
    @RateLimit(prefix = "myxhs:inventory:reinit", maxRequests = 2, windowSeconds = 60)
    public R<Void> reinitStock(
            @Valid @RequestBody ReinitRequest request,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        int bucketCount = request.getBucketCount() != null ? request.getBucketCount() : defaultBucketCount;
        inventoryService.reinitStock(request.getSkuId(), bucketCount);
        return R.ok();
    }

    /**
     * 查询可用库存
     */
    @GetMapping("/stock/{skuId}")
    public R<StockVO> getStock(@PathVariable Long skuId) {
        return R.ok(inventoryService.getStock(skuId));
    }

    // ==================== 对账管理接口 ====================

    /**
     * 手动触发全量库存对账（管理接口，需 X-Admin-Call 校验）
     */
    @PostMapping("/internal/reconcile")
    @RateLimit(prefix = "myxhs:inventory:reconcile", maxRequests = 2, windowSeconds = 60,
            message = "对账操作过于频繁，每分钟最多2次")
    public R<Integer> reconcile(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        int repairCount = inventoryReconcileJob.doReconcile();
        return R.ok(repairCount);
    }

    // ==================== TCC 接口 ====================

    /**
     * TCC Try: 预扣库存（冻结）
     * <p>
     * 订单服务调用，冻结可用库存到 freezing_stock。
     * 使用 TCC Fence 表保证幂等 + 防悬挂。
     * </p>
     */
    @PostMapping("/tcc/try")
    public R<Boolean> tccTryDeduct(@Valid @RequestBody TccDeductRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        try {
            boolean result = inventoryTccService.tryDeductStock(
                request.getXid(), request.getBranchId(), request.getSkuItems()
            );
            return R.ok(result);
        } catch (InventoryTccService.InsufficientStockException e) {
            return R.fail(400, e.getMessage());
        }
    }

    /**
     * TCC Confirm: 确认扣减
     * <p>
     * 订单支付成功后调用，将 freezing_stock 转为实际扣减。
     * </p>
     */
    @PostMapping("/tcc/confirm")
    public R<Void> tccConfirmDeduct(@Valid @RequestBody TccDeductRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        inventoryTccService.confirmDeductStock(
            request.getXid(), request.getBranchId(), request.getSkuItems()
        );
        return R.ok();
    }

    /**
     * TCC Cancel: 取消预扣（解冻）
     * <p>
     * 订单取消/超时未支付时调用，将 freezing_stock 恢复到 available_stock。
     * </p>
     */
    @PostMapping("/tcc/cancel")
    public R<Void> tccCancelDeduct(@Valid @RequestBody TccDeductRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        inventoryTccService.cancelDeductStock(
            request.getXid(), request.getBranchId(), request.getSkuItems()
        );
        return R.ok();
    }
}
