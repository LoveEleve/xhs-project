package com.myxhs.cart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车单项商品VO
 */
@Data
@Builder
public class CartItemVO {

    /** SKU ID */
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商品名称（SKU名称） */
    private String name;

    /** 价格 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 数量 */
    private Integer quantity;

    /** 是否选中 */
    private Boolean checked;

    /** 规格属性JSON */
    private String specs;

    /** 商品图片（SPU首图） */
    private String image;

    /** 是否有效（下架/无库存标记为失效） */
    private Boolean valid;

    /** 失效原因（商品下架/库存不足） */
    private String invalidReason;

    /** 加购时间戳（用于排序） */
    private Long addedAt;
}
