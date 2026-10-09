package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /** 标记消息成功 */
    /**
     * 清理已发送的本地消息（status=1 成功行一单一行、永久保留 → 表随订单量线性增长；
     * 消息只承担"下单事务与 MQ 投递"的过渡，7 天后无重查价值）。无分片键 → ShardingSphere
     * 广播到各分片，各片删至多 limit 行。
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_local_message WHERE status = 1 AND created_at < #{cutoff} LIMIT #{limit}")
    int deleteSentBefore(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") int limit);

    @Update("UPDATE t_local_message SET status = 1, updated_at = NOW() WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);

    /** 按事务ID+分片键标记已投递（事务消息 COMMIT 后调用，避免 LocalMessageRetryJob 重复补发）
     *  带 user_id 精确路由分片，避免 ShardingSphere 广播 */
    @Update("UPDATE t_local_message SET status = 1, updated_at = NOW() " +
            "WHERE transaction_id = #{transactionId} AND user_id = #{userId} AND status = 0")
    int markSuccessByTransactionId(@Param("transactionId") String transactionId,
                                   @Param("userId") Long userId);

    /** T-072（2026-08-14）：补发失败重试/死信状态更新——按 id 广播更新（不碰分片键 user_id，
     *  原 updateById 全字段更新含 user_id 触发 ShardingSphere "can not update sharding value"） */
    @Update("UPDATE t_local_message SET status = #{status}, retry_count = #{retryCount}, " +
            "next_retry_time = #{nextRetryTime}, updated_at = NOW() WHERE id = #{id}")
    int updateRetryStatus(@Param("id") Long id, @Param("status") int status,
                          @Param("retryCount") int retryCount,
                          @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime);

    /** T-072：死信重投失败计数（负数表示死信重试次数，status 保持 3） */
    @Update("UPDATE t_local_message SET retry_count = #{retryCount}, updated_at = NOW() WHERE id = #{id}")
    int updateDeadRetry(@Param("id") Long id, @Param("retryCount") int retryCount);

    /** 根据事务ID查询（事务消息回查使用） */
    @Select("SELECT * FROM t_local_message WHERE transaction_id = #{transactionId} LIMIT 1")
    LocalMessage selectByTransactionId(@Param("transactionId") String transactionId);

    /** 根据事务ID + userId 查询（分库分表精确路由） */
    @Select("SELECT * FROM t_local_message WHERE transaction_id = #{transactionId} " +
            "AND user_id = #{userId} LIMIT 1")
    LocalMessage selectByTransactionIdAndUserId(@Param("transactionId") String transactionId,
                                                 @Param("userId") Long userId);
}
