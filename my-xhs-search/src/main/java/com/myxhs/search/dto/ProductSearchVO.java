package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品搜索结果项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchVO {

    private Long spuId;
    private Long skuId;
    private String name;
    private Long categoryId;
    private String categoryName;
    private String brandName;
    private BigDecimal price;
    private String image;
    private Long sales;
    private String createdAt;

    /** 高亮名称 */
    private String highlightName;
}
