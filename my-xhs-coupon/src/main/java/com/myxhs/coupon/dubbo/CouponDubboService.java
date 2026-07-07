package com.myxhs.coupon.dubbo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 优惠券 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于订单服务高频调用优惠券查询/核销/退还链路。
 * </p>
 */
public interface CouponDubboService {

    /**
     * 查询用户可用优惠券列表
     *
     * @param userId 用户 ID
     * @return 优惠券列表（Map 格式，避免依赖 VO）
     */
    List<Map<String, Object>> getAvailableCoupons(Long userId);

    /**
     * 核销优惠券（下单时调用）
     *
     * @param userCouponId 用户优惠券记录 ID
     * @param orderId      订单 ID
     * @param orderAmount  订单金额（用于门槛校验）
     * @param userId       用户 ID
     * @return true=核销成功, false=失败
     */
    boolean useCoupon(Long userCouponId, Long orderId, BigDecimal orderAmount, Long userId);

    /**
     * 退还优惠券（取消订单时调用）
     *
     * @param userCouponId 用户优惠券记录 ID
     * @param orderId      订单 ID
     * @param userId       用户 ID
     */
    void returnCoupon(Long userCouponId, Long orderId, Long userId);
}
