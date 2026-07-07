package com.myxhs.coupon.provider;

import com.myxhs.coupon.dubbo.CouponDubboService;
import com.myxhs.coupon.dto.request.ReturnCouponRequest;
import com.myxhs.coupon.dto.request.UseCouponRequest;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 优惠券 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的优惠券查询和操作 RPC 接口。
 * 主要用于首页聚合服务和订单服务的优惠券查询/核销/退还。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class CouponDubboServiceImpl implements CouponDubboService {

    private final CouponService couponService;

    @Override
    public List<Map<String, Object>> getAvailableCoupons(Long userId) {
        List<UserCouponVO> coupons = couponService.getAvailableCoupons(userId);
        return coupons.stream().map(this::voToMap).collect(Collectors.toList());
    }

    @Override
    public boolean useCoupon(Long userCouponId, Long orderId, BigDecimal orderAmount, Long userId) {
        UseCouponRequest request = new UseCouponRequest();
        request.setUserCouponId(userCouponId);
        request.setOrderId(orderId);
        request.setOrderAmount(orderAmount);
        couponService.useCoupon(userId, request);
        return true;
    }

    @Override
    public void returnCoupon(Long userCouponId, Long orderId, Long userId) {
        ReturnCouponRequest request = new ReturnCouponRequest();
        request.setUserCouponId(userCouponId);
        request.setOrderId(orderId);
        couponService.returnCoupon(userId, request);
    }

    private Map<String, Object> voToMap(Object vo) {
        Map<String, Object> map = new HashMap<>();
        if (vo == null) return map;
        try {
            for (var field : vo.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                map.put(field.getName(), field.get(vo));
            }
        } catch (Exception e) {
            log.warn("[CouponDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}
