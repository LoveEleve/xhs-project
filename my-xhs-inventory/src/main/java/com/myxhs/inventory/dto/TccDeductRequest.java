package com.myxhs.inventory.dto;

import com.myxhs.inventory.service.InventoryTccService.SkuItem;
import lombok.Data;

import java.util.List;

/**
 * TCC 库存扣减请求 DTO
 */
@Data
public class TccDeductRequest {

    /** 全局事务ID，如 "order:ORDER20260101001" */
    private String xid;

    /** 分支事务ID */
    private Long branchId;

    /** SKU 扣减列表 */
    private List<SkuItem> skuItems;
}
