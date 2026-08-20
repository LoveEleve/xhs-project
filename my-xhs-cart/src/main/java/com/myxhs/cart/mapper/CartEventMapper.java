package com.myxhs.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.cart.entity.CartEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 购物车事件流水 Mapper（append-only）
 */
@Mapper
public interface CartEventMapper extends BaseMapper<CartEvent> {
}
