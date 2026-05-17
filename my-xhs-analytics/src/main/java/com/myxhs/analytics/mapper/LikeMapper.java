package com.myxhs.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.analytics.entity.Like;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 点赞 Mapper
 */
@Mapper
public interface LikeMapper extends BaseMapper<Like> {

    /**
     * 根据用户ID、业务类型和业务ID删除点赞记录
     */
    @Delete("DELETE FROM t_like WHERE user_id = #{userId} AND biz_type = #{bizType} AND biz_id = #{bizId}")
    int deleteByUserAndBiz(@Param("userId") Long userId, @Param("bizType") Integer bizType, @Param("bizId") Long bizId);
}
