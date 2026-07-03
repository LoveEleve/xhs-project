package com.myxhs.counter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.counter.dto.CounterFlushDTO;
import com.myxhs.counter.entity.Counter;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 计数 Mapper
 */
@Mapper
public interface CounterMapper extends BaseMapper<Counter> {

    /**
     * 批量 Upsert（INSERT ON DUPLICATE KEY UPDATE）
     * <p>
     * 利用唯一索引 uk_target_count(target_type, target_id, count_type)：
     * - 首次写入：INSERT
     * - 后续更新：count_value = count_value + delta（增量累加）
     * </p>
     * <p>
     * 注意：调用前必须按 (target_type, target_id, count_type) 排序，避免死锁。
     * </p>
     */
    @Insert("<script>" +
            "INSERT INTO t_counter (id, target_type, target_id, count_type, count_value) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.targetType}, #{item.targetId}, #{item.countType}, #{item.delta})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE count_value = count_value + VALUES(count_value), " +
            " updated_at = NOW()" +
            "</script>")
    void batchUpsert(@Param("list") List<CounterFlushDTO> list);

    /**
     * 查询指定目标的指定计数
     */
    @Select("SELECT * FROM t_counter WHERE target_type = #{targetType} AND target_id = #{targetId} AND count_type = #{countType} AND deleted = 0")
    Counter selectByTarget(@Param("targetType") int targetType, @Param("targetId") long targetId, @Param("countType") int countType);

    /**
     * 批量查询计数（Redis 未命中时的一次性 MySQL 回退，避免 N+1）
     * <p>
     * 使用 WHERE (target_type, target_id, count_type) IN (...) 一次查询替代 N 次循环单查。
     * </p>
     */
    @Select("<script>" +
            "SELECT * FROM t_counter WHERE deleted = 0 AND " +
            "(target_type, target_id, count_type) IN " +
            "<foreach collection='queries' item='q' open='(' close=')' separator=','>" +
            "(#{q.targetType}, #{q.targetId}, #{q.countType})" +
            "</foreach>" +
            "</script>")
    List<Counter> selectByTargets(@Param("queries") List<CounterBatchQuery> queries);

    /**
     * 游标分页查询（对账修复用）
     * <p>
     * 按 id 升序分批扫描，避免 OFFSET 深分页性能问题。
     * </p>
     */
    @Select("SELECT * FROM t_counter WHERE id > #{lastId} AND deleted = 0 ORDER BY id ASC LIMIT #{batchSize}")
    List<Counter> selectBatchAfterId(@Param("lastId") long lastId, @Param("batchSize") int batchSize);

    /**
     * 更新计数值（对账修复用）
     */
    @Update("UPDATE t_counter SET count_value = #{countValue}, updated_at = NOW() WHERE id = #{id}")
    void updateCountValue(@Param("id") long id, @Param("countValue") long countValue);
}
