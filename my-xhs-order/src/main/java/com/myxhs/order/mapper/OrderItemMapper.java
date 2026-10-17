package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /** 子单下的全部行（发货拦截校验/展示分组；带分片键路由） */
    @Select("SELECT * FROM t_order_item WHERE sub_order_id = #{subOrderId} AND user_id = #{userId} ORDER BY id ASC")
    List<OrderItem> selectBySubOrderId(@Param("subOrderId") Long subOrderId, @Param("userId") Long userId);
}
