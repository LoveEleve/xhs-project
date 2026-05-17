package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.OrderFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 订单服务 Feign Client
 */
@FeignClient(name = "my-xhs-order",
        fallbackFactory = OrderFeignFallbackFactory.class)
public interface OrderFeignClient {

    /**
     * 订单详情
     */
    @GetMapping("/api/order/{orderId}")
    R<Map<String, Object>> getOrderDetail(@RequestHeader("X-User-Id") Long userId,
                                          @PathVariable("orderId") Long orderId);

    /**
     * 我的订单列表
     */
    @GetMapping("/api/order/list")
    R<List<Map<String, Object>>> getUserOrders(@RequestHeader("X-User-Id") Long userId,
                                               @RequestParam(value = "status", required = false) Integer status);
}
