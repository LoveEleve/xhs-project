package com.myxhs.inventory.service;

import com.myxhs.common.tcc.TccFenceService;
import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存 TCC 服务 — Try-Confirm-Cancel 三阶段库存操作
 *
 * Try: 冻结库存（available_stock -= qty, freezing_stock += qty）
 * Confirm: 确认扣减（freezing_stock -= qty）
 * Cancel: 解冻库存（freezing_stock -= qty, available_stock += qty）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryTccService {

    private final InventoryMapper inventoryMapper;
    private final TccFenceService tccFenceService;

    /**
     * Try: 预扣库存（冻结）
     * @param xid 全局事务ID，如 "order:ORDER20260101001"
     * @param branchId 分支事务ID
     * @param skuItems [{skuId, quantity}]
     * @return true-预扣成功，false-库存不足或被悬挂拒绝
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public boolean tryDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
        // Step 1: Fence 检查（幂等 + 防悬挂）
        if (!tccFenceService.tryFence(xid, branchId, "tryDeductStock")) {
            log.warn("[TCC Try] 悬挂拒绝: xid={}, branchId={}", xid, branchId);
            return false;
        }

        // Step 2: 逐个 SKU 冻结库存
        for (SkuItem item : skuItems) {
            // UPDATE t_inventory
            // SET available_stock = available_stock - #{qty},
            //     freezing_stock = freezing_stock + #{qty}
            // WHERE sku_id = #{skuId} AND available_stock >= #{qty}
            int affected = inventoryMapper.tryFreeze(item.getSkuId(), item.getQuantity());
            if (affected == 0) {
                log.warn("[TCC Try] 库存不足: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
                // Try 阶段失败，由调用方决定是否 Cancel 已冻结的 SKU
                throw new InsufficientStockException("库存不足: skuId=" + item.getSkuId());
            }
            log.info("[TCC Try] 冻结库存: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
        }

        return true;
    }

    /**
     * Confirm: 确认扣减（冻结转实际扣减）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void confirmDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
        // Step 1: Fence 更新
        tccFenceService.confirmFence(xid, branchId);

        // Step 2: 确认扣减每个 SKU
        for (SkuItem item : skuItems) {
            // UPDATE t_inventory
            // SET freezing_stock = freezing_stock - #{qty}
            // WHERE sku_id = #{skuId} AND freezing_stock >= #{qty}
            inventoryMapper.confirmFreeze(item.getSkuId(), item.getQuantity());
            log.info("[TCC Confirm] 确认扣减: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
        }
    }

    /**
     * Cancel: 取消预扣（解冻库存）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void cancelDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
        // Step 1: Fence 空回滚
        tccFenceService.cancelFence(xid, branchId, "tryDeductStock");

        // Step 2: 解冻每个 SKU
        for (SkuItem item : skuItems) {
            // UPDATE t_inventory
            // SET available_stock = available_stock + #{qty},
            //     freezing_stock = freezing_stock - #{qty}
            // WHERE sku_id = #{skuId} AND freezing_stock >= #{qty}
            int affected = inventoryMapper.cancelFreeze(item.getSkuId(), item.getQuantity());
            if (affected > 0) {
                log.info("[TCC Cancel] 解冻库存: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
            }
        }
    }

    /**
     * SKU 扣减项
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class SkuItem {
        private Long skuId;
        private Integer quantity;
    }

    /**
     * 库存不足异常
     */
    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String message) {
            super(message);
        }
    }
}
