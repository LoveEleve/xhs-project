package com.myxhs.cart.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品服务 Feign 客户端
 * <p>
 * 通过 Nacos 服务发现调用，由 Spring Cloud LoadBalancer 负责负载均衡。
 * </p>
 */
@FeignClient(name = "my-xhs-product", fallbackFactory = ProductFeignFallbackFactory.class)
public interface ProductFeignClient {

    /**
     * 获取 SKU 详情
     */
    @GetMapping("/api/product/sku/{skuId}")
    R<SkuDTO> getSkuDetail(@PathVariable("skuId") Long skuId);

    /**
     * 批量获取 SKU 详情（内部接口）
     * <p>
     * 注意：当前 Product 服务未提供批量接口，此方法暂时通过循环单查实现。
     * 后续 Product 服务补充批量接口后，直接切换到批量调用。
     * </p>
     */
    @GetMapping("/api/product/sku/batch")
    R<List<SkuDTO>> batchGetSkuDetails(@org.springframework.web.bind.annotation.RequestParam("skuIds") List<Long> skuIds);

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
    }
}
