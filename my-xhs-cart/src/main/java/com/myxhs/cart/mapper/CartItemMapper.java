package com.myxhs.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.cart.entity.CartItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 购物车 Mapper
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 查询所有有购物车记录的用户 ID（去重）
     * 用于对账修复任务
     */
    @Select("SELECT DISTINCT user_id FROM t_cart_item")
    List<Long> selectDistinctUserIds();
}
