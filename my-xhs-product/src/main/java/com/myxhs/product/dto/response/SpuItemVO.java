package com.myxhs.product.dto.response;

import lombok.Data;

import java.util.List;

/**
 * SPU 列表项响应（不含 SKU 列表，轻量级）
 */
@Data
public class SpuItemVO {

    private Long id;

    /** 商品名称 */
    private String name;

    /** 分类ID */
    private Long categoryId;

    /** 商品图片URL列表 */
    private List<String> images;

    /** 状态：0-下架 1-上架 */
    private Integer status;
}
