package com.myxhs.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品 SPU 实体（Standard Product Unit，标准产品单元）
 * <p>
 * SPU 描述商品的共有属性（名称、品牌、详情），一个 SPU 下有多个 SKU。
 * images 以 JSON 数组字符串存储，业务层通过 JSON 序列化/反序列化转换为 List。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_spu")
public class Spu extends BaseEntity {

    /** 商品名称 */
    private String name;

    /** 分类ID */
    private Long categoryId;

    /** 品牌ID */
    private Long brandId;

    /** 商品描述 */
    private String description;

    /** 商品图片列表(JSON数组) */
    private String images;

    /** 状态：0-下架 1-上架 */
    private Integer status;
}
