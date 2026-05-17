package com.myxhs.product.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 响应
 */
@Data
public class SkuVO {

    private Long id;

    /** SPU ID */
    private Long spuId;

    /** SKU名称 */
    private String name;

    /** 价格 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 库存 */
    private Integer stock;

    /** 规格属性JSON */
    private String specs;

    /** 状态：0-下架 1-上架 */
    private Integer status;
}
