package com.myxhs.cart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车项实体
 * <p>
 * 注意：购物车表没有 deleted 字段（不需要逻辑删除，直接物理删除），
 * 因此不继承 BaseEntity，避免 MyBatis-Plus 自动追加 deleted=0 条件。
 * </p>
 */
@Data
@TableName("t_cart_item")
public class CartItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** SKU ID */
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /** 是否选中：0-否 1-是 */
    private Integer checked;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
