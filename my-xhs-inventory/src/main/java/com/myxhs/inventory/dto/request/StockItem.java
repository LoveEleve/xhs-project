package com.myxhs.inventory.dto.request;

import lombok.Data;

/**
 * 库存条目（C-008 items 契约：confirm/release 随请求携带 SKU 列表）
 * <p>
 * 背景：预扣记录收敛为 per-(sku,order) 单键后，confirm/release 无法再从"订单 Hash"发现 SKU 列表；
 * order 侧是 SKU 事实源（订单明细不可变）→ items 随请求走（避免"订单→SKU 索引"跨 slot 无法原子的方案）。
 * </p>
 */
@Data
public class StockItem {

    /** SKU ID */
    private Long skuId;

    /** 数量（confirm/release 以预扣记录内数量为准，本字段用于日志/对账核对，可空） */
    private Integer quantity;
}
