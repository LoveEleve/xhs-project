package com.myxhs.inventory.service;

import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * L2 落账事务内幂等（C-008 F4）
 * <p>
 * 把"逻辑事件幂等（t_inventory_l2_event INSERT IGNORE）+ 库存变更"放进**同一事务**：
 * - 补发/重投（新 msgId）/崩溃重投：inbox 命中 → 跳过（不双扣）
 * - 处理中崩溃：事务回滚 → 无记录 → 重投重新应用（不丢）
 * </p>
 * <p>
 * 对比旧实现（Redis 前置 msgId 标记）：前置标记在崩溃后会把重投拦死 → 事件永久丢失；
 * 本实现是"完成态"语义——只有事务提交（含 inbox 行）才视为已处理。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryL2InboxService {

    private final InventoryMapper inventoryMapper;

    /**
     * 事务内应用一次：INSERT IGNORE 命中（0 行）→ 返回 false（此前已应用，跳过）；
     * 首次（1 行）→ 执行 operation（库存变更）→ 一并提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyOnce(Long orderId, Long skuId, String action, Runnable operation) {
        if (inventoryMapper.insertL2Event(orderId, skuId, action) == 0) {
            return false;
        }
        operation.run();
        return true;
    }
}
