package com.myxhs.cart.dubbo;

import java.util.Map;

/**
 * 购物车 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务高频调用购物车服务：
 * - 购物车列表（购物车页面）
 * - 购物车商品数量（导航栏角标）
 * </p>
 */
public interface CartDubboService {

    /**
     * 购物车列表
     */
    Map<String, Object> getCartList(Long userId);

    /**
     * 购物车商品数量（角标）
     */
    Map<String, Integer> getCartCount(Long userId);
}
