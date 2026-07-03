package com.myxhs.inventory.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.InventoryInitRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.dto.response.StockVO;
import com.myxhs.inventory.service.InventoryService;
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

    @Value("${inventory.bucket.default-count:2}")
    private int defaultBucketCount;

    /**
     * 库存初始化（DB → Redis 分桶）
     * <p>
     * 管理后台调用，将 SKU 库存初始化到 Redis 分桶中。
     * </p>
     */
    @PostMapping("/init")
    @RateLimit(prefix = "inventory:init", maxRequests = 5, windowSeconds = 60)
    public R<Void> initStock(@Valid @RequestBody InventoryInitRequest request) {
        inventoryService.initStock(request);
        return R.ok();
    }

    /**
     * 预扣减（下单时调用）
     * <p>
     * 订单服务通过 Feign 调用，L1 Redis 分桶预扣。
     * </p>
     */
    @PostMapping("/preDeduct")
    public R<Void> preDeduct(@Valid @RequestBody PreDeductRequest request) {
        inventoryService.preDeduct(request);
        return R.ok();
    }

    /**
     * 确认扣减（支付成功后调用）
     */
    @PostMapping("/confirm")
    public R<Void> confirmDeduct(@Valid @RequestBody ConfirmDeductRequest request) {
        inventoryService.confirmDeduct(request);
        return R.ok();
    }

    /**
     * 释放库存（取消订单/超时未支付）
     */
    @PostMapping("/release")
    public R<Void> releaseStock(@Valid @RequestBody ReleaseStockRequest request) {
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
    @RateLimit(prefix = "inventory:reinit", maxRequests = 2, windowSeconds = 60)
    public R<Void> reinitStock(@Valid @RequestBody InventoryInitRequest request) {
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
}
