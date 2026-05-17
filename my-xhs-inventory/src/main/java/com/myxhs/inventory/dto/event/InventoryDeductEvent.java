package com.myxhs.inventory.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

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
public class InventoryDeductEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    private Long orderId;

    /** SKU ID */
    private Long skuId;

    /** 扣减数量 */
    private Integer quantity;

    /** 操作类型：PRE_DEDUCT / CONFIRM / RELEASE */
    private String action;
}
