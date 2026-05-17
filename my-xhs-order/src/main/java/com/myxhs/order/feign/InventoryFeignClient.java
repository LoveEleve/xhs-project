package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 库存服务 Feign Client
 * <p>
 * 用于订单取消/超时时释放预扣库存。
 * </p>
 */
@FeignClient(name = "my-xhs-inventory",
        fallbackFactory = InventoryFeignFallbackFactory.class)
public interface InventoryFeignClient {

    /**
     * 释放库存（取消订单/超时未支付）
     */
    @PostMapping("/api/inventory/release")
    R<Void> releaseStock(@RequestBody Map<String, Object> request);
}
