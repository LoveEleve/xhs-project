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

    /**
     * TCC Try: 冻结库存（available_stock -= qty, freezing_stock += qty）
     * @return affected rows (1=成功, 0=库存不足)
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock - #{qty}, " +
            "freezing_stock = freezing_stock + #{qty}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND available_stock >= #{qty}")
    int tryFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /**
     * TCC Confirm: 确认扣减（freezing_stock -= qty）
     * @return affected rows
     */
    @Update("UPDATE t_inventory SET freezing_stock = freezing_stock - #{qty}, " +
            "updated_at = NOW() WHERE sku_id = #{skuId} AND freezing_stock >= #{qty}")
    int confirmFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /**
     * TCC Cancel: 解冻库存（freezing_stock -= qty, available_stock += qty）
     * @return affected rows
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock + #{qty}, " +
            "freezing_stock = freezing_stock - #{qty}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND freezing_stock >= #{qty}")
    int cancelFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);
}
