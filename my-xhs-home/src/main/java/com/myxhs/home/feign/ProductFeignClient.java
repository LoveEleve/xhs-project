package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.ProductFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 商品服务 Feign Client
 */
@FeignClient(name = "my-xhs-product",
        fallbackFactory = ProductFeignFallbackFactory.class)
public interface ProductFeignClient {

    /**
     * SPU 详情（含 SKU 列表）
     */
    @GetMapping("/api/product/spu/{spuId}")
    R<Map<String, Object>> getSpuDetail(@PathVariable("spuId") Long spuId);

    /**
     * SKU 详情
     */
    @GetMapping("/api/product/sku/{skuId}")
    R<Map<String, Object>> getSkuDetail(@PathVariable("skuId") Long skuId);
}
