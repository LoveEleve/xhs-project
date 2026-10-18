package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.SubOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 子订单 Mapper（ITER-001 拆单）
 * <p>
 * 分库分表注意事项（与 OrderMapper 同口径）：
 * 1. 所有查询/更新必须带 user_id 条件（分片键路由），否则全分片广播；
 * 2. selectById 不可用，改用 selectBySubOrderNo（subOrderNo + userId）或 selectByOrderId。
 * </p>
 */
@Mapper
public interface SubOrderMapper extends BaseMapper<SubOrder> {

    /** 订单全部子单（父单聚合/展示用；一次批量查询，禁止逐子单查询） */
    @Select("SELECT * FROM t_sub_order WHERE order_id = #{orderId} AND user_id = #{userId} AND deleted = 0 ORDER BY id ASC")
    List<SubOrder> selectByOrderId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /** 按子订单号查（履约/售后入口） */
    @Select("SELECT * FROM t_sub_order WHERE sub_order_no = #{subOrderNo} AND user_id = #{userId} AND deleted = 0 LIMIT 1")
    SubOrder selectBySubOrderNo(@Param("subOrderNo") String subOrderNo, @Param("userId") Long userId);

    /**
     * 全量扫描（C-004 P1 回填用；游标分页，无分片键 → 全路由 scatter+归并——一次性运维路径，
     * 不用于线上查询链路）。
     */
    @Select("SELECT id, order_id, order_no, sub_order_no, user_id, seller_id, status, "
            + "total_amount, discount_amount, pay_amount, created_at, updated_at "
            + "FROM t_sub_order WHERE id > #{cursor} AND deleted = 0 ORDER BY id ASC LIMIT #{limit}")
    List<SubOrder> scanByCursor(@Param("cursor") long cursor, @Param("limit") int limit);

    /** 发货（CAS：待发货 → 已发货，带时间戳） */
    @Update("UPDATE t_sub_order SET status = 2, delivered_at = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND deleted = 0")
    int markDelivered(@Param("id") Long id, @Param("userId") Long userId);

    /** 确认收货（CAS：已发货 → 已完成，带时间戳） */
    @Update("UPDATE t_sub_order SET status = 3, completed_at = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 2 AND deleted = 0")
    int markCompleted(@Param("id") Long id, @Param("userId") Long userId);

    /** 支付成功：订单全部子单 0→1（批量推进，幂等） */
    @Update("UPDATE t_sub_order SET status = 1, updated_at = NOW() "
            + "WHERE order_id = #{orderId} AND user_id = #{userId} AND status = 0 AND deleted = 0")
    int advanceAllToPaid(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /** 未支付终结：订单全部子单 0→4（与父单同本地事务，批量取消） */
    @Update("UPDATE t_sub_order SET status = 4, cancelled_at = NOW(), updated_at = NOW() "
            + "WHERE order_id = #{orderId} AND user_id = #{userId} AND status = 0 AND deleted = 0")
    int cancelAll(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /** 全退收敛：订单全部子单（1/2/3）→5（批量退款，幂等） */
    @Update("UPDATE t_sub_order SET status = 5, refunded_at = NOW(), updated_at = NOW() "
            + "WHERE order_id = #{orderId} AND user_id = #{userId} AND status IN (1, 2, 3) AND deleted = 0")
    int refundAll(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
