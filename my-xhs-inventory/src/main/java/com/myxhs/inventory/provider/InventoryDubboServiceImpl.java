package com.myxhs.inventory.provider;

import com.myxhs.inventory.api.dubbo.InventoryDubboService;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.dto.response.StockVO;
import com.myxhs.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

/**
 * 库存 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的库存操作 RPC 接口。
 * 主要用于订单服务的库存预扣减/释放/确认链路。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class InventoryDubboServiceImpl implements InventoryDubboService {

    private final InventoryService inventoryService;

    @Override
    public boolean preDeduct(Long skuId, Integer quantity, Long orderId, Long userId) {
        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        request.setOrderId(orderId);
        request.setUserId(userId);
        try {
            inventoryService.preDeduct(request);
            return true;
        } catch (Exception e) {
            log.warn("[InventoryDubbo] 预扣减失败: skuId={}, orderId={}, error={}", skuId, orderId, e.getMessage());
            return false;
        }
    }

    @Override
    public void releaseStock(String orderId) {
        ReleaseStockRequest request = new ReleaseStockRequest();
        request.setOrderId(Long.valueOf(orderId));
        inventoryService.releaseStock(request);
    }

    @Override
    public void confirmDeduct(String orderId) {
        ConfirmDeductRequest request = new ConfirmDeductRequest();
        request.setOrderId(Long.valueOf(orderId));
        inventoryService.confirmDeduct(request);
    }
}
