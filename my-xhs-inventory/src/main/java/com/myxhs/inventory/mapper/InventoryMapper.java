package com.myxhs.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.inventory.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** T-071：退款回补库存（available_stock +qty——退款回补语义，非 locked→available） */
    @org.apache.ibatis.annotations.Update("UPDATE t_inventory SET available_stock = available_stock + #{quantity}, " +
            "updated_at = NOW() WHERE sku_id = #{skuId} AND deleted = 0")
    int refundRestoreStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /** T-079：预扣幂等表——INSERT IGNORE 探测（冲突返回 0=已存在=幂等；
     *  不用 ON DUPLICATE：JDBC 默认 found rows 语义会返回 1 导致探测失效） */
    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO t_inventory_prededuct_idem (order_id, sku_id) " +
            "VALUES (#{orderId}, #{skuId})")
    int insertPredeductIdem(@Param("orderId") Long orderId, @Param("skuId") Long skuId);

    /** T-079：预扣失败（库存不足/未初始化）时删除幂等占位，允许重试 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_inventory_prededuct_idem WHERE order_id = #{orderId} AND sku_id = #{skuId}")
    int deletePredeductIdem(@Param("orderId") Long orderId, @Param("skuId") Long skuId);

    /**
     * 清理过期预扣幂等占位（成功预扣的占位永久保留 → 表随订单量线性增长；
     * 幂等只需覆盖"预扣记录/TTL + MQ 重投窗口"，7 天后无重投可能）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_inventory_prededuct_idem WHERE created_at < #{before} LIMIT #{limit}")
    int deleteStalePredeductIdem(@Param("before") java.time.LocalDateTime before, @Param("limit") int limit);

    /** 已发送 outbox 记录保留 7 天（idx_status_created 支撑） */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_inventory_outbox WHERE status = 1 AND created_at < #{before} LIMIT #{limit}")
    int deleteSentOutbox(@Param("before") java.time.LocalDateTime before, @Param("limit") int limit);

    /** 已处理/转人工的补偿记录保留 90 天（idx_status_retry_created 支撑） */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_inventory_compensation WHERE status IN (1, 2) AND created_at < #{before} LIMIT #{limit}")
    int deleteSettledCompensation(@Param("before") java.time.LocalDateTime before, @Param("limit") int limit);

    /**
     * TCC Try: 冻结库存（available_stock -= qty, freezing_stock += qty）
     * @return affected rows (1=成功, 0=库存不足)
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock - #{qty}, " +
            "freezing_stock = freezing_stock + #{qty}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND available_stock >= #{qty} AND deleted = 0")
    int tryFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /**
     * TCC Confirm: 确认扣减（freezing_stock -= qty）
     * @return affected rows
     */
    @Update("UPDATE t_inventory SET freezing_stock = freezing_stock - #{qty}, " +
            "updated_at = NOW() WHERE sku_id = #{skuId} AND freezing_stock >= #{qty} AND deleted = 0")
    int confirmFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /**
     * TCC Cancel: 解冻库存（freezing_stock -= qty, available_stock += qty）
     * @return affected rows
     */
    @Update("UPDATE t_inventory SET available_stock = available_stock + #{qty}, " +
            "freezing_stock = freezing_stock - #{qty}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND freezing_stock >= #{qty} AND deleted = 0")
    int cancelFreeze(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /**
     * 对账专用：仅更新 locked_stock（以 Redis 在途预扣为权威重算）
     */
    @Update("UPDATE t_inventory SET locked_stock = #{lockedStock}, updated_at = NOW() "
            + "WHERE sku_id = #{skuId} AND deleted = 0")
    int updateLockedStockOnly(@Param("skuId") Long skuId, @Param("lockedStock") int lockedStock);

    /**
     * 对账专用：仅更新 available_stock（不触碰 locked_stock/freezing_stock），
     * 避免 updateById 全字段盲写把并发 L2 的 locked_stock 变更回滚（幻影锁复活）
     */
    @Update("UPDATE t_inventory SET available_stock = #{available} WHERE sku_id = #{skuId} AND deleted = 0")
    int updateAvailableStockOnly(@Param("skuId") Long skuId, @Param("available") int available);

    // ==================== TCC 冻结明细（xid 维度） ====================

    /**
     * 写入冻结明细（Try 阶段，重复 Try 由 fence 幂等拦截，此处 ON DUPLICATE 防御并发）
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO t_tcc_freeze_detail (xid, branch_id, sku_id, quantity, status, created_at) " +
            "VALUES (#{xid}, #{branchId}, #{skuId}, #{quantity}, 1, NOW(3)) " +
            "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), status = 1")
    int insertFreezeDetail(@Param("xid") String xid, @Param("branchId") Long branchId,
            @Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 冻结明细状态转换（乐观锁：仅允许从指定前状态转换，防并发双 Confirm/Cancel）
     */
    @Update("UPDATE t_tcc_freeze_detail SET status = #{toStatus} " +
            "WHERE xid = #{xid} AND branch_id = #{branchId} AND sku_id = #{skuId} AND status = #{fromStatus}")
    int updateFreezeDetailStatus(@Param("xid") String xid, @Param("branchId") Long branchId,
            @Param("skuId") Long skuId, @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus);

    /**
     * 查询超时的冻结明细（status=1 且创建时间早于阈值），供超时 Job 按 xid 逐个取消
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_tcc_freeze_detail WHERE status = 1 " +
            "AND created_at < #{cutoff} ORDER BY created_at ASC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectExpiredFreezeDetails(@Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /**
     * 存储库存事件 Outbox 记录
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO t_inventory_outbox (id, order_id, sku_id, quantity, action, status, created_at) " +
            "VALUES (#{eventId}, #{orderId}, #{skuId}, #{quantity}, #{action}, 0, NOW()) " +
            "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), action = VALUES(action), status = 0, created_at = NOW()")
    int insertOutboxEvent(@Param("eventId") Long eventId, @Param("orderId") Long orderId, @Param("skuId") Long skuId,
            @Param("quantity") int quantity, @Param("action") String action);

    /**
     * 查询待发送的 Outbox 事件
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_inventory_outbox WHERE status = 0 " +
            "AND created_at < #{cutoff} " +
            "AND (next_retry_time IS NULL OR next_retry_time <= NOW()) ORDER BY id ASC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectPendingOutbox(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 补发失败：递增重试次数并设置下次可发时间（指数退避+抖动，P2/2026-09-27） */
    @org.apache.ibatis.annotations.Update("UPDATE t_inventory_outbox SET retry_count = retry_count + 1, " +
            "next_retry_time = #{nextRetryTime} WHERE id = #{eventId}")
    int markOutboxRetry(@Param("eventId") Long eventId, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /**
     * 标记 Outbox 事件已发送
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_inventory_outbox SET status = 1 WHERE id = #{eventId}")
    int markOutboxSent(@Param("eventId") Long eventId);

    /**
     * 取消 Outbox 事件（syncSend 失败且已回滚 Redis 时调用，
     * 防止 OutboxSenderJob 补发已回滚的事件导致 MySQL 幻影扣减）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_inventory_outbox WHERE id = #{eventId} AND status = 0")
    int cancelOutboxEvent(@Param("eventId") Long eventId);

    /**
     * 写入补偿记录（回滚失败时需要人工/自动重试）
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO t_inventory_compensation (order_id, sku_id, quantity, fail_reason, created_at) " +
            "VALUES (#{orderId}, #{skuId}, #{quantity}, #{reason}, NOW())")
    int insertCompensation(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
            @Param("quantity") int quantity, @Param("reason") String reason);

    /**
     * 插入补偿记录（带类型）：type 1-预扣回滚 2-退款回补
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO t_inventory_compensation "
            + "(order_id, sku_id, quantity, type, user_id, fail_reason, status, retry_count) "
            + "VALUES (#{orderId}, #{skuId}, #{quantity}, #{type}, #{userId}, #{reason}, 0, 0)")
    int insertCompensationWithType(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
            @Param("quantity") int quantity, @Param("type") int type, @Param("userId") Long userId,
            @Param("reason") String reason);

    /**
     * 是否已有同 (订单, SKU, 类型) 的待处理补偿（避免异常反复触发时重复堆积）
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_inventory_compensation "
            + "WHERE order_id = #{orderId} AND sku_id = #{skuId} AND type = #{type} AND status = 0")
    int countPendingCompensation(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
            @Param("type") int type);

    /**
     * 查询待处理的补偿记录
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_inventory_compensation WHERE status = 0 " +
            "AND retry_count < #{maxRetry} AND created_at < #{cutoff} ORDER BY id ASC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectPendingCompensation(@Param("cutoff") LocalDateTime cutoff,
            @Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 标记补偿记录已处理
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_inventory_compensation SET status = 1 WHERE id = #{id}")
    int markCompensationResolved(@Param("id") Long id);

    /**
     * 增加补偿重试次数
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_inventory_compensation SET retry_count = retry_count + 1, " +
            "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 2 ELSE 0 END WHERE id = #{id}")
    int incrementCompensationRetry(@Param("id") Long id, @Param("maxRetry") int maxRetry);
}
