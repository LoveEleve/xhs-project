package com.myxhs.product.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 响应
 * <p>
 * 注意：不含 stock 字段。SKU 表的 stock 是创建时的冗余占位值（从不更新），
 * 展示会误导前端。真实库存以 inventory 服务为准（/api/inventory/stock/{skuId}）。
 * </p>
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

    /** 规格属性JSON */
    private String specs;

    /** 状态：0-下架 1-上架 */
    private Integer status;
}
