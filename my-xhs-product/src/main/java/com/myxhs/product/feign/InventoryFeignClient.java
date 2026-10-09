package com.myxhs.product.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 库存服务 Feign Client（商品域 → 库存域）
 * <p>
 * 用途：新建 SKU 后自动初始化库存（DB + Redis 分桶）。
 * 原实现只落 SKU 冗余 stock 字段，不初始化 inventory → 新商品下单直接"库存未初始化"失败。
 * </p>
 */
@FeignClient(name = "my-xhs-inventory",
        configuration = InternalCallFeignConfig.class)
public interface InventoryFeignClient {

    /**
     * 初始化 SKU 库存（管理端/内部服务均可调用）
     */
    @PostMapping("/api/inventory/init")
    R<Void> initStock(@RequestBody InitRequest request);

    /** 初始化请求（与 inventory 域 InventoryInitRequest 字段对齐，避免跨服务依赖 DTO） */
    class InitRequest {
        private Long skuId;
        private Integer totalStock;
        private Integer bucketCount;

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public Integer getTotalStock() { return totalStock; }
        public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }
        public Integer getBucketCount() { return bucketCount; }
        public void setBucketCount(Integer bucketCount) { this.bucketCount = bucketCount; }
    }
}
