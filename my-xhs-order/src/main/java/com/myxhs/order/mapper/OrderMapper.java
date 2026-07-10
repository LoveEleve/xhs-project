package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 Mapper
 * <p>
 * 分库分表注意事项：
 * 1. 所有查询/更新必须带 user_id 条件，否则 ShardingSphere 会扫描所有分片
 * 2. selectById 不可用（缺少分片键），改用 selectOne + user_id + id 联合查询
 * 3. selectTimeoutOrders 是全量扫描（定时任务兜底），性能可接受
 * </p>
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 取消订单（乐观锁 + 分片键路由）
     */
    @Update("UPDATE t_order SET status = #{status}, cancelled_at = #{cancelledAt}, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0 AND deleted = 0")
    int cancelOrder(@Param("id") Long id, @Param("userId") Long userId,
                    @Param("status") int status, @Param("cancelledAt") LocalDateTime cancelledAt);

    /**
     * 标记已支付（乐观锁 + 分片键路由）
     */
    @Update("UPDATE t_order SET status = 1, paid_at = #{paidAt}, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0 AND deleted = 0")
    int markPaid(@Param("id") Long id, @Param("userId") Long userId,
                 @Param("paidAt") LocalDateTime paidAt);

    /**
     * 标记已完成（乐观锁 + 分片键路由）
     */
    @Update("UPDATE t_order SET status = 3, completed_at = #{completedAt}, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 2 AND deleted = 0")
    int markCompleted(@Param("id") Long id, @Param("userId") Long userId,
                      @Param("completedAt") LocalDateTime completedAt);

    /**
     * 标记已退款（乐观锁 + 分片键路由）
     * <p>
     * 只有"已支付(status=1)"的订单才能退款。
     * 由支付服务退款成功后通过 Feign 回调触发。
     * </p>
     */
    @Update("UPDATE t_order SET status = 5, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND deleted = 0")
    int markRefunded(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 通用乐观锁状态更新（Event Sourcing 使用）
     * 只有当前状态匹配时才执行更新，防止并发覆盖
     * @param id 订单 ID
     * @param userId 用户 ID（分片键）
     * @param currentStatus 当前状态（乐观锁条件）
     * @param targetStatus 目标状态
     * @return 受影响行数（0 表示并发冲突）
     */
    @Update("UPDATE t_order SET status = #{targetStatus}, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = #{currentStatus} AND deleted = 0")
    int updateStatusWithLock(@Param("id") Long id, @Param("userId") Long userId,
                              @Param("currentStatus") Integer currentStatus,
                              @Param("targetStatus") Integer targetStatus);

    /**
     * 查询超时未支付订单（30分钟前创建且仍为待付款）
     * <p>
     * 注意：此查询不带分片键，ShardingSphere 会扫描所有分片。
     * 这是定时任务兜底方案，低频执行（每分钟一次），性能可接受。
     * </p>
     * <p>
     * 使用 id > lastId 游标分页，避免 OFFSET 深分页性能问题，
     * 同时保证每次扫描都能处理到所有超时订单（不会遗漏）。
     * </p>
     */
    @Select("SELECT * FROM t_order WHERE status = 0 AND deleted = 0 " +
            "AND created_at < #{deadline} AND id > #{lastId} ORDER BY id ASC LIMIT #{limit}")
    List<Order> selectTimeoutOrders(@Param("deadline") LocalDateTime deadline,
                                    @Param("lastId") Long lastId,
                                    @Param("limit") int limit);

    /**
     * 查询最近创建的订单（映射表补录使用）
     * <p>
     * 注意：此查询不带分片键，ShardingSphere 会扫描所有分片。
     * 定时任务低频执行（每 5 分钟一次），性能可接受。
     * </p>
     */
    @Select("SELECT * FROM t_order WHERE deleted = 0 " +
            "AND created_at >= #{since} AND id > #{lastId} ORDER BY id ASC LIMIT #{limit}")
    List<Order> selectRecentOrders(@Param("since") LocalDateTime since,
                                   @Param("lastId") Long lastId,
                                   @Param("limit") int limit);
}
