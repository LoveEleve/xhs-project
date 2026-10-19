package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 确认扣减请求（支付成功后调用；C-008 items 契约）
 */
@Data
public class ConfirmDeductRequest {

    /** 订单ID */
    @NotNull(message = "orderId不能为空")
    private Long orderId;

    /**
     * 订单 SKU 列表（order 侧事实源随请求携带；C-008：预扣记录为 per-(sku,order) 单键，无法从订单 Hash 发现）
     */
    private List<StockItem> items;
}
