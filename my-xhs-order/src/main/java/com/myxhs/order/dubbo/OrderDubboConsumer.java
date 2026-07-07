package com.myxhs.order.dubbo;

import com.myxhs.coupon.dubbo.CouponDubboService;
import com.myxhs.inventory.api.dubbo.InventoryDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单服务 Dubbo Consumer
 * <p>
 * 管理订单服务对外部服务的 Dubbo 调用引用：
 * - 库存服务：预扣减/释放/确认
 * - 优惠券服务：可用优惠券查询
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDubboConsumer {

    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private InventoryDubboService inventoryDubboService;

    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private CouponDubboService couponDubboService;

    public boolean preDeduct(Long skuId, Integer quantity, Long orderId, Long userId) {
        return inventoryDubboService.preDeduct(skuId, quantity, orderId, userId);
    }

    public void releaseStock(String orderId) {
        inventoryDubboService.releaseStock(orderId);
    }

    public void confirmDeduct(String orderId) {
        inventoryDubboService.confirmDeduct(orderId);
    }

    public List<Map<String, Object>> getAvailableCoupons(Long userId) {
        return couponDubboService.getAvailableCoupons(userId);
    }
}
