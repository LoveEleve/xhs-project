package com.myxhs.cart.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品服务 Feign 客户端
 * <p>
 * 通过 Nacos 服务发现调用，由 Spring Cloud LoadBalancer 负责负载均衡。
 * </p>
 */
@FeignClient(name = "my-xhs-product", fallbackFactory = ProductFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface ProductFeignClient {

    /**
     * 批量获取 SKU 详情（内部接口）
     * <p>
     * Product 服务已提供批量接口（/api/product/sku/batch），cart 已通过此接口调用。
     * </p>
     */
    @GetMapping("/api/product/sku/batch")
    R<List<SkuDTO>> batchGetSkuDetails(@org.springframework.web.bind.annotation.RequestParam("skuIds") List<Long> skuIds);

    /**
     * 单条 SKU 详情（加购存在性校验用）
     * <p>
     * product 的 getSkuDetail 不过滤 status（下架 SKU 详情仍返回），不存在返回 30002。
     * </p>
     */
    @GetMapping("/api/product/sku/{skuId}")
    R<SkuDTO> getSkuDetail(@org.springframework.web.bind.annotation.PathVariable("skuId") Long skuId);

    /**
     * SKU DTO（Feign 响应解析用）
     */
    @lombok.Data
    class SkuDTO {
        private Long id;
        private Long spuId;
        private String name;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private Integer stock;
        private String specs;
        private Integer status;
        /** T-047：所属 SPU 状态（0-下架 1-上架）——cart 判断 SPU 维度有效性 */
        private Integer spuStatus;
    }
}
