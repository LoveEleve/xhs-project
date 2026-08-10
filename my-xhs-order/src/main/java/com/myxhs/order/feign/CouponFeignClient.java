package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 优惠券服务 Feign Client
 */
@FeignClient(name = "my-xhs-coupon",
        fallbackFactory = CouponFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface CouponFeignClient {

    /**
     * 查询优惠券折扣（下单前调用，不核销）
     */
    @GetMapping("/api/coupon/discount/{id}")
    R<BigDecimal> getCouponDiscount(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable("id") Long userCouponId,
                                    @RequestParam("orderAmount") BigDecimal orderAmount);

    /**
     * 核销优惠券（创建订单时调用）
     * @return 折扣金额
     */
    @PostMapping("/api/coupon/use")
    R<BigDecimal> useCoupon(@RequestHeader("X-User-Id") Long userId,
                            @RequestBody Map<String, Object> request);

    /**
     * 退还优惠券（取消订单时调用）
     */
    @PostMapping("/api/coupon/return")
    R<Void> returnCoupon(@RequestHeader("X-User-Id") Long userId,
                         @RequestBody Map<String, Object> request);
}
