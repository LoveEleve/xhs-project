package com.myxhs.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品 SKU 实体（Stock Keeping Unit，库存量单元）
 * <p>
 * SKU 描述商品的规格变体（颜色+尺码组合），每个 SKU 有独立的价格和库存。
 * specs 以 JSON 字符串存储规格属性，如 {"颜色":"红色","尺码":"XL"}。
 * stock 字段为冗余字段，实际库存由库存服务管理。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sku")
public class Sku extends BaseEntity {

    /** SPU ID */
    private Long spuId;

    /** SKU名称 */
    private String name;

    /** 价格 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 库存(冗余，实际由库存服务管理) */
    private Integer stock;

    /** 规格属性(JSON) */
    private String specs;

    /** 状态：0-下架 1-上架 */
    private Integer status;
}
