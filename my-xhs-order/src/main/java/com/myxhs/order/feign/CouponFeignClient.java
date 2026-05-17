package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 优惠券服务 Feign Client
 * <p>
 * 用于订单取消时退还优惠券。
 * </p>
 */
@FeignClient(name = "my-xhs-coupon",
        fallbackFactory = CouponFeignFallbackFactory.class)
public interface CouponFeignClient {

    /**
     * 退还优惠券（取消订单时调用）
     */
    @PostMapping("/api/coupon/return")
    R<Void> returnCoupon(@RequestHeader("X-User-Id") Long userId,
                         @RequestBody Map<String, Object> request);
}
