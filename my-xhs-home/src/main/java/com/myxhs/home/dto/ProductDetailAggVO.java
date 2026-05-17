package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品详情聚合 VO（BFF 层返回给前端）
 * <p>
 * 聚合来源：
 * - SPU 详情（含 SKU 列表） → product 服务
 * - 各 SKU 库存 → inventory 服务
 * - 商品计数（收藏数等） → counter 服务
 * - 关联笔记（种草笔记） → content 服务 / search 服务
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailAggVO {

    // ========== SPU 基本信息 ==========
    private Long spuId;
    private String name;
    private String description;
    private List<String> images;
    private Long categoryId;
    private String categoryName;
    private Integer status;

    // ========== SKU 列表（含库存） ==========
    private List<SkuWithStockVO> skuList;

    // ========== 商品计数 ==========
    private Long collectCount;
    private Long viewCount;

    // ========== 关联种草笔记 ==========
    private List<NoteCardVO> relatedNotes;

    /**
     * SKU + 库存聚合
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuWithStockVO {
        private Long skuId;
        private String skuName;
        private BigDecimal price;
        private String image;
        private Map<String, String> specValues;
        private Integer availableStock;
        private Boolean hasStock;
    }
}
