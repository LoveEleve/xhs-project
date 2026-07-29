package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 重新初始化请求（与 init 不同，不需要 totalStock——从 MySQL 读取真实库存）
 */
@Data
public class ReinitRequest {

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 分桶数（可选，默认使用配置值；范围 1-32） */
    @Min(value = 1, message = "分桶数至少为1")
    @Max(value = 32, message = "分桶数不能超过32")
    private Integer bucketCount;
}
