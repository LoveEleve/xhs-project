package com.myxhs.product.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 分类树节点响应
 * <p>
 * 递归结构，children 包含子分类列表。
 * </p>
 */
@Data
public class CategoryTreeVO {

    private Long id;

    /** 分类名称 */
    private String name;

    /** 父分类ID */
    private Long parentId;

    /** 层级 */
    private Integer level;

    /** 排序值 */
    private Integer sort;

    /** 分类图标 */
    private String icon;

    /** 子分类列表 */
    private List<CategoryTreeVO> children;
}
