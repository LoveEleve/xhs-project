package com.myxhs.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.analytics.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
