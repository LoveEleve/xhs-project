package com.myxhs.product.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 更新 SPU 请求
 * <p>
 * 所有字段可选——未传的字段保持原值。
 * name 允许不传或传 null/空串（需客户端明确传 null 而非漏传，Jackson 反序列化默认 null）。
 * </p>
 */
@Data
public class SpuUpdateRequest {

    /** 商品名称（可选，不传则保持原值） */
    private String name;

    /** 分类ID（可为空，表示不更新分类） */
    private Long categoryId;

    /** 品牌ID（可为空） */
    private Long brandId;

    /** 商品描述 */
    private String description;

    /** 商品图片URL列表 */
    private List<String> images;
}
