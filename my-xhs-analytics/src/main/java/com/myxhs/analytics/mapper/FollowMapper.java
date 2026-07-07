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
     */
    @Select("SELECT DISTINCT user_id FROM t_follow WHERE id > #{lastId} ORDER BY id LIMIT #{batchSize}")
    List<Long> selectDistinctUserIds(@Param("lastId") long lastId, @Param("batchSize") int batchSize);

    /**
     * 获取当前批次的最大ID（用于分页推进）
     */
    @Select("SELECT MAX(id) FROM t_follow WHERE id > #{lastId} LIMIT #{batchSize}")
    Long selectMaxIdByLastId(@Param("lastId") long lastId, @Param("batchSize") int batchSize);
}
