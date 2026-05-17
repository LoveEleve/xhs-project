package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.InventoryFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 库存服务 Feign Client
 */
@FeignClient(name = "my-xhs-inventory",
        fallbackFactory = InventoryFeignFallbackFactory.class)
public interface InventoryFeignClient {

    /**
     * 查询可用库存
     */
    @GetMapping("/api/inventory/stock/{skuId}")
    R<Map<String, Object>> getStock(@PathVariable("skuId") Long skuId);
}
