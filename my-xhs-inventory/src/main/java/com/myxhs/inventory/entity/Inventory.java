package com.myxhs.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存实体
 * <p>
 * 对应表 t_inventory，每个 SKU 一条记录。
 * available_stock：可用库存（可被扣减的）
 * locked_stock：锁定库存（已预扣未确认的）
 * 实际总库存 = available_stock + locked_stock
 * </p>
 */
@Data
@TableName("t_inventory")
public class Inventory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SKU ID */
    private Long skuId;

    /** 可用库存 */
    private Integer availableStock;

    /** 锁定库存（预扣未确认） */
    private Integer lockedStock;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
