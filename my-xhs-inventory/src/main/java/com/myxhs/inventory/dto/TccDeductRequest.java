package com.myxhs.inventory.dto;

import com.myxhs.inventory.service.InventoryTccService.SkuItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * TCC 库存扣减请求 DTO
 */
@Data
public class TccDeductRequest {

    /** 全局事务ID，如 "order:ORDER20260101001" */
    @NotBlank(message = "全局事务ID不能为空")
    private String xid;

    /** 分支事务ID */
    @NotNull(message = "分支事务ID不能为空")
    private Long branchId;

    /** SKU 扣减列表 */
    @NotEmpty(message = "SKU列表不能为空")
    @Valid
    private List<SkuItem> skuItems;
}
