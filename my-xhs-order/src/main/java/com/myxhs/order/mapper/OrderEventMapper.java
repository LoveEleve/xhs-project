package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.OrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface OrderEventMapper extends BaseMapper<OrderEvent> {
    @Select("SELECT * FROM t_order_event WHERE order_id = #{orderId} ORDER BY event_seq ASC")
    List<OrderEvent> findByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM t_order_event WHERE order_id = #{orderId} ORDER BY event_seq DESC LIMIT 1")
    OrderEvent findLastByOrderId(@Param("orderId") Long orderId);
}
