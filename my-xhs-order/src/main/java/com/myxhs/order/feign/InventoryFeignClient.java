package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 库存服务 Feign Client
 * <p>
 * 用于订单创建前置库存校验、订单取消/超时时释放预扣库存。
 * </p>
 */
@FeignClient(name = "my-xhs-inventory",
        fallbackFactory = InventoryFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface InventoryFeignClient {

    /**
     * 查询库存（下单前校验）
     */
    @GetMapping("/api/inventory/stock/{skuId}")
    R<Map<String, Object>> queryStock(@PathVariable Long skuId);

    /**
     * 释放库存（取消订单/超时未支付）
     */
    @PostMapping("/api/inventory/release")
    R<Void> releaseStock(@RequestBody Map<String, Object> request);

    /** T-071：退款回补库存（全额退款后 Redis/MySQL 库存加回） */
    @PostMapping("/api/inventory/refund-restore")
    R<Void> refundRestore(@RequestBody Map<String, Object> request);

    /**
     * 确认扣减（支付成功后调用，locked_stock 正式扣除）
     */
    @PostMapping("/api/inventory/confirm")
    R<Void> confirmDeduct(@RequestBody Map<String, Object> request);
}
