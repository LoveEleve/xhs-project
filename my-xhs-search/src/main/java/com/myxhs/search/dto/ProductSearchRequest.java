package com.myxhs.search.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品搜索请求
 */
@Data
public class ProductSearchRequest {

    /** 搜索关键词 */
    private String keyword;

    /** 分类ID */
    private Long categoryId;

    /** 最低价格 */
    private BigDecimal minPrice;

    /** 最高价格 */
    private BigDecimal maxPrice;

    /** 排序方式：relevance(相关度)/price_asc(价格升序)/price_desc(价格降序)/sales(销量) */
    private String sort = "relevance";

    /** 每页大小 */
    private Integer size = 20;

    /** Search After 游标 */
    private String searchAfter;
}
