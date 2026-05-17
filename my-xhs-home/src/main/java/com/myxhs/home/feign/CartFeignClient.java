package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.CartFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 购物车服务 Feign Client
 */
@FeignClient(name = "my-xhs-cart",
        fallbackFactory = CartFeignFallbackFactory.class)
public interface CartFeignClient {

    /**
     * 购物车列表
     */
    @GetMapping("/api/cart/list")
    R<Map<String, Object>> getCartList(@RequestHeader("X-User-Id") Long userId);

    /**
     * 购物车商品数量（角标）
     */
    @GetMapping("/api/cart/count")
    R<Map<String, Integer>> getCartCount(@RequestHeader("X-User-Id") Long userId);
}
