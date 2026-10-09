package com.myxhs.product.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建 SKU 请求
 */
@Data
public class SkuCreateRequest {

    @NotNull(message = "SPU ID不能为空")
    private Long spuId;

    @NotBlank(message = "SKU名称不能为空")
    private String name;

    @NotNull(message = "价格不能为空")
    @Positive(message = "价格必须大于0")
    private BigDecimal price;

    /** 原价（可选） */
    @Positive(message = "原价必须大于0")
    private BigDecimal originalPrice;

    /** 初始库存 */
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 规格属性JSON，如 {"颜色":"红色","尺码":"XL"} */
    @jakarta.validation.constraints.Size(max = 1024, message = "规格属性过长(最多1024字符)")
    private String specs;
}
