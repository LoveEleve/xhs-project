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
        // Step 1: Fence 检查（幂等 + 防悬挂）——根据结果决定是否执行业务
        TccFenceService.TryFenceResult fenceResult = tccFenceService.tryFence(xid, branchId, "tryDeductStock");
        if (fenceResult == TccFenceService.TryFenceResult.SUSPENDED) {
            log.warn("[TCC Try] 悬挂拒绝: xid={}, branchId={}", xid, branchId);
            return false;
        }
        if (fenceResult == TccFenceService.TryFenceResult.DUPLICATE) {
            // 重复 Try 幂等命中：跳过业务，否则会双冻结 freezing_stock
            log.info("[TCC Try] 幂等命中跳过冻结: xid={}, branchId={}", xid, branchId);
            return true;
        }

        // Step 2: 逐个 SKU 冻结库存 + 写入 xid 维度冻结明细（供超时 Job 精确取消）
        for (SkuItem item : skuItems) {
            int affected = inventoryMapper.tryFreeze(item.getSkuId(), item.getQuantity());
            if (affected == 0) {
                log.warn("[TCC Try] 库存不足: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
                // Try 阶段失败，由调用方决定是否 Cancel 已冻结的 SKU
                throw new InsufficientStockException("库存不足: skuId=" + item.getSkuId());
            }
            inventoryMapper.insertFreezeDetail(xid, branchId, item.getSkuId(), item.getQuantity());
            log.info("[TCC Try] 冻结库存: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
        }

        return true;
    }

    /**
     * Confirm: 确认扣减（冻结转实际扣减）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void confirmDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
        // Step 1: Fence 状态转换——仅首次转换成功才执行业务
        TccFenceService.ConfirmFenceResult fenceResult = tccFenceService.confirmFence(xid, branchId);
        if (fenceResult == TccFenceService.ConfirmFenceResult.SKIP_DUPLICATE) {
            // 重复 Confirm 幂等命中：跳过业务，否则会双扣 freezing_stock（偷其他订单的冻结）
            log.info("[TCC Confirm] 幂等命中跳过确认: xid={}, branchId={}", xid, branchId);
            return;
        }
        if (fenceResult == TccFenceService.ConfirmFenceResult.REJECTED) {
            log.error("[TCC Confirm] Fence拒绝(无Try或已Cancel): xid={}, branchId={}", xid, branchId);
            return;
        }

        // Step 2: 确认扣减每个 SKU + 更新冻结明细状态（1→2）
        for (SkuItem item : skuItems) {
            inventoryMapper.confirmFreeze(item.getSkuId(), item.getQuantity());
            inventoryMapper.updateFreezeDetailStatus(xid, branchId, item.getSkuId(), 1, 2);
            log.info("[TCC Confirm] 确认扣减: xid={}, skuId={}, qty={}", xid, item.getSkuId(), item.getQuantity());
        }
    }

    /**
     * Cancel: 取消预扣（解冻库存）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void cancelDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
        // Step 1: Fence 状态转换——仅首次转换成功才执行业务
        TccFenceService.CancelFenceResult fenceResult = tccFenceService.cancelFence(xid, branchId, "tryDeductStock");
        if (fenceResult == TccFenceService.CancelFenceResult.SKIP) {
            // 空回滚(Try未执行)或重复Cancel：跳过业务，否则双解冻凭空增加 available_stock
            log.info("[TCC Cancel] 空回滚/幂等命中跳过解冻: xid={}, branchId={}", xid, branchId);
            return;
        }
        if (fenceResult == TccFenceService.CancelFenceResult.REJECTED_CONFIRMED) {
            log.error("[TCC Cancel] 已Confirm不可取消: xid={}, branchId={}", xid, branchId);
            return;
        }

        // Step 2: 解冻每个 SKU + 更新冻结明细状态（1→3）
        for (SkuItem item : skuItems) {
            int affected = inventoryMapper.cancelFreeze(item.getSkuId(), item.getQuantity());
            if (affected > 0) {
                inventoryMapper.updateFreezeDetailStatus(xid, branchId, item.getSkuId(), 1, 3);
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
        /** SKU ID */
        @jakarta.validation.constraints.NotNull(message = "skuId不能为空")
        private Long skuId;
        /** 数量（必须为正数） */
        @jakarta.validation.constraints.Min(value = 1, message = "扣减数量至少为1")
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
