package com.myxhs.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品分类实体（三级分类树）
 * <p>
 * 采用 parent_id 自关联实现树形结构：
 * - 一级分类：parent_id = 0
 * - 二级分类：parent_id = 一级分类ID
 * - 三级分类：parent_id = 二级分类ID
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category")
public class Category extends BaseEntity {

    /** 分类名称 */
    private String name;

    /** 父分类ID（0为一级分类） */
    private Long parentId;

    /** 层级：1-一级 2-二级 3-三级 */
    private Integer level;

    /** 排序值（越小越靠前） */
    private Integer sort;

    /** 分类图标URL */
    private String icon;

    /** 状态：0-禁用 1-启用 */
    private Integer status;
}
