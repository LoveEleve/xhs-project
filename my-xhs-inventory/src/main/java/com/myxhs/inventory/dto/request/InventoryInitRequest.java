package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库存初始化请求
 */
@Data
public class InventoryInitRequest {

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 总库存 */
    @NotNull(message = "库存不能为空")
    @Min(value = 1, message = "库存至少为1")
    private Integer totalStock;

    /** 分桶数（可选，默认使用配置值；范围 1-32） */
    @Min(value = 1, message = "分桶数至少为1")
    @Max(value = 32, message = "分桶数不能超过32")
    private Integer bucketCount;
}
