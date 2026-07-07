package com.myxhs.product.api.dubbo;

import java.util.Map;

/**
 * 商品 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务和购物车服务高频调用商品服务：
 * - SPU 详情（商品详情页、首页推荐）
 * - SKU 详情（购物车商品信息、下单确认）
 * </p>
 */
public interface ProductDubboService {

    /**
     * SPU 详情（含 SKU 列表）
     */
    Map<String, Object> getSpuDetail(Long spuId);

    /**
     * SKU 详情
     */
    Map<String, Object> getSkuDetail(Long skuId);
}
