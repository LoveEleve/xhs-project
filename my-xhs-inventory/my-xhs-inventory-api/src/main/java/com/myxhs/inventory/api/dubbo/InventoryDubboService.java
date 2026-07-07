package com.myxhs.inventory.api.dubbo;

/**
 * 库存 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于订单服务高频调用库存扣减链路：
 * - 预扣减（下单时）
 * - 回滚（MQ 发送失败/订单取消）
 * - 确认（支付成功）
 * </p>
 */
public interface InventoryDubboService {

    /**
     * 预扣减库存（下单时调用）
     *
     * @param skuId    SKU ID
     * @param quantity 扣减数量
     * @param orderId  订单 ID
     * @param userId   用户 ID
     * @return true=扣减成功, false=库存不足
     */
    boolean preDeduct(Long skuId, Integer quantity, Long orderId, Long userId);

    /**
     * 释放库存（取消订单/超时未支付）
     *
     * @param orderId 订单 ID
     */
    void releaseStock(String orderId);

    /**
     * 确认扣减（支付成功后调用）
     *
     * @param orderId 订单 ID
     */
    void confirmDeduct(String orderId);
}
