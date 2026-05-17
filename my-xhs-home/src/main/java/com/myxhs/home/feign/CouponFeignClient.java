package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.CouponFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * 优惠券服务 Feign Client
 */
@FeignClient(name = "my-xhs-coupon",
        fallbackFactory = CouponFeignFallbackFactory.class)
public interface CouponFeignClient {

    /**
     * 可用优惠券列表（下单时展示）
     */
    @GetMapping("/api/coupon/user/available")
    R<List<Map<String, Object>>> getAvailableCoupons(@RequestHeader("X-User-Id") Long userId);
}
