package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.OrderEvent;
import org.apache.ibatis.annotations.Insert;
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

    /**
     * 幂等插入事件（INSERT IGNORE）
     * 依赖唯一索引 uk_order_event_seq (order_id, event_seq) 防重
     * @return 受影响行数（0 表示已存在，幂等跳过）
     */
    @Insert("INSERT IGNORE INTO t_order_event (order_id, user_id, event_type, from_status, to_status, payload, event_seq, event_time) " +
            "VALUES (#{orderId}, #{userId}, #{eventType}, #{fromStatus}, #{toStatus}, #{payload}, #{eventSeq}, #{eventTime})")
    int insertIgnore(OrderEvent event);
}
