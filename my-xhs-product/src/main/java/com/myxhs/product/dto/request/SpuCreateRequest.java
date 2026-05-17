package com.myxhs.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建 SPU 请求
 */
@Data
public class SpuCreateRequest {

    @NotBlank(message = "商品名称不能为空")
    private String name;

    @NotNull(message = "分类ID不能为空")
    private Long categoryId;

    /** 品牌ID（可选） */
    private Long brandId;

    /** 商品描述 */
    private String description;

    /** 商品图片URL列表 */
    private List<String> images;
}
