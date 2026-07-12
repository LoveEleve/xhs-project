package com.myxhs.inventory.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myxhs.common.event.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 库存扣减事件（MQ 异步扣 DB）
 * <p>
 * L1 Redis 预扣成功后，发送此事件到 MQ，Consumer 异步扣减 MySQL。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class InventoryDeductEvent extends AbstractDomainEvent<InventoryDeductEvent> {

    /** 订单ID */
    private Long orderId;

    /** SKU ID */
    private Long skuId;

    /** 扣减数量 */
    private Integer quantity;

    /** 操作类型：PRE_DEDUCT / CONFIRM / RELEASE */
    private String action;

    @Override
    public String getEventType() {
        return "INVENTORY_DEDUCT";
    }

    @Override
    public String getSource() {
        return "my-xhs-inventory";
    }

    @Override
    @JsonIgnore
    public InventoryDeductEvent getPayload() {
        return this;
    }
}
