package com.myxhs.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    private BigDecimal price;

    /** 原价（可选） */
    private BigDecimal originalPrice;

    /** 初始库存 */
    private Integer stock;

    /** 规格属性JSON，如 {"颜色":"红色","尺码":"XL"} */
    private String specs;
}
