package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 释放库存请求（取消订单/超时未支付；C-008 items 契约）
 */
@Data
public class ReleaseStockRequest {

    /** 订单ID */
    @NotNull(message = "orderId不能为空")
    private Long orderId;

    /**
     * 订单 SKU 列表（order 侧事实源随请求携带；C-008：预扣记录为 per-(sku,order) 单键，无法从订单 Hash 发现）
     */
    private List<StockItem> items;
}
