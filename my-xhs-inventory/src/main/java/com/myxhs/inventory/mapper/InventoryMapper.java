package com.myxhs.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.inventory.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存 Mapper
 */
@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 扣减可用库存 + 增加锁定库存（乐观锁：available_stock >= quantity）
     * <p>
     * 用于 L2 MQ 异步扣 DB 场景。
     * WHERE available_stock >= quantity 保证不会扣成负数。
     * </p>
     *
     * @return 影响行数（0=库存不足，1=扣减成功）
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock - #{quantity}, " +
            "locked_stock = locked_stock + #{quantity} " +
            "WHERE sku_id = #{skuId} AND available_stock >= #{quantity} AND deleted = 0")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 确认扣减：减少锁定库存（支付成功后调用）
     *
     * @return 影响行数
     */
    @Update("UPDATE t_inventory SET locked_stock = locked_stock - #{quantity} " +
            "WHERE sku_id = #{skuId} AND locked_stock >= #{quantity} AND deleted = 0")
    int confirmDeduct(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 释放库存：锁定库存回退到可用库存（取消/超时）
     *
     * @return 影响行数
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock + #{quantity}, " +
            "locked_stock = locked_stock - #{quantity} " +
            "WHERE sku_id = #{skuId} AND locked_stock >= #{quantity} AND deleted = 0")
    int releaseStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
