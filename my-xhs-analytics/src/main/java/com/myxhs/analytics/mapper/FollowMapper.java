package com.myxhs.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.analytics.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 关注关系 Mapper
 */
@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 根据用户ID和被关注用户ID删除关注关系
     */
    @Delete("DELETE FROM t_follow WHERE user_id = #{userId} AND follow_user_id = #{followUserId}")
    int deleteByUserIdAndFollowUserId(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    /**
     * 分页获取去重后的用户ID列表（用于计数对账）
     * 扫描 user_id 和 follow_user_id 两个列，确保关注者和粉丝身份都被覆盖
     * 使用子查询先按 id 排序取 batch，再 DISTINCT，避免 DISTINCT + ORDER BY id 的不兼容
     */
    @Select("<script>SELECT DISTINCT uid FROM ("
            + "(SELECT user_id AS uid, id FROM t_follow WHERE id &gt; #{lastId} ORDER BY id LIMIT #{batchSize})"
            + " UNION "
            + "(SELECT follow_user_id AS uid, id FROM t_follow WHERE id &gt; #{lastId} ORDER BY id LIMIT #{batchSize})"
            + ") t</script>")
    List<Long> selectDistinctUserIds(@Param("lastId") long lastId, @Param("batchSize") int batchSize);

    /**
     * 获取当前批次的最大ID（用于分页推进）
     * 通过子查询限制范围后取 MAX，确保每批推进不超过 batchSize 行
     */
    @Select("SELECT MAX(id) FROM (SELECT id FROM t_follow WHERE id > #{lastId} ORDER BY id LIMIT #{batchSize}) t")
    Long selectMaxIdByLastId(@Param("lastId") long lastId, @Param("batchSize") int batchSize);

    /**
     * 查询某用户所有关注目标的 user_id（用于 Redis ↔ MySQL 关系对账）
     */
    @Select("SELECT follow_user_id FROM t_follow WHERE user_id = #{userId}")
    List<Long> selectFollowUserIdsByUserId(@Param("userId") Long userId);

    /**
     * 删除指定 ID 的关注关系记录（用于对账清理孤儿行）
     */
    @Delete("DELETE FROM t_follow WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 查询某用户所有粉丝的 user_id（用于粉丝侧 Redis ↔ MySQL 关系对账）
     */
    @Select("SELECT user_id FROM t_follow WHERE follow_user_id = #{userId}")
    List<Long> selectFollowerUserIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询某用户全部关注行（含关注时间，用于 Redis 侧数据缺失时按 MySQL 重建 ZSet）
     */
    @Select("SELECT id, user_id, follow_user_id, created_at FROM t_follow WHERE user_id = #{userId}")
    List<Follow> selectFollowRowsByUserId(@Param("userId") Long userId);

    /**
     * 查询某用户全部粉丝行（含关注时间，用于 Redis 侧数据缺失时按 MySQL 重建 ZSet）
     */
    @Select("SELECT id, user_id, follow_user_id, created_at FROM t_follow WHERE follow_user_id = #{userId}")
    List<Follow> selectFollowerRowsByUserId(@Param("userId") Long userId);
}
