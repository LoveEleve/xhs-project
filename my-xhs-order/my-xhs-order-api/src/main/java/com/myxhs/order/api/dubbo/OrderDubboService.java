package com.myxhs.order.api.dubbo;

import java.util.List;
import java.util.Map;

/**
 * 订单 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务和支付服务高频调用订单服务：
 * - 订单详情（用户订单详情页）
 * - 用户订单列表（我的订单页）
 * </p>
 */
public interface OrderDubboService {

    /**
     * 订单详情
     */
    Map<String, Object> getOrderDetail(Long userId, Long orderId);

    /**
     * 我的订单列表
     */
    List<Map<String, Object>> getUserOrders(Long userId, Integer status);
}
