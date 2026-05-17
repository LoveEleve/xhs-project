package com.myxhs.product.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SPU 详情响应（含 SKU 列表）
 */
@Data
public class SpuDetailVO {

    private Long id;

    /** 商品名称 */
    private String name;

    /** 分类ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 品牌ID */
    private Long brandId;

    /** 商品描述 */
    private String description;

    /** 商品图片URL列表 */
    private List<String> images;

    /** 状态：0-下架 1-上架 */
    private Integer status;

    /** SKU 列表 */
    private List<SkuVO> skuList;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
