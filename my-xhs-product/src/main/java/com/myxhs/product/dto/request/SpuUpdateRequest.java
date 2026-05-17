package com.myxhs.product.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 更新 SPU 请求
 */
@Data
public class SpuUpdateRequest {

    /** 商品名称 */
    private String name;

    /** 分类ID */
    private Long categoryId;

    /** 品牌ID */
    private Long brandId;

    /** 商品描述 */
    private String description;

    /** 商品图片URL列表 */
    private List<String> images;
}
