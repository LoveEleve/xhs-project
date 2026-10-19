package com.myxhs.counter.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * like 权威重建查询（C-009 D2-B：跨库读 my_xhs_analytics.t_like）
 * <p>
 * 先例：search 模块跨库读 product/analytics 均显式库前缀（IncrementalIndexSyncJob/RecommendComputeJob）；
 * 同实例多 schema 部署下直接前缀查询，避免跨模块 Mapper 依赖。
 * </p>
 */
@Mapper
public interface LikeAuthorityMapper {

    /** 按 (bizType, bizId) 取全部点赞用户（t_like.biz_type: 1-笔记 2-评论） */
    @Select("SELECT user_id FROM my_xhs_analytics.t_like WHERE biz_type = #{bizType} AND biz_id = #{bizId}")
    List<Long> selectUserIdsByBiz(@Param("bizType") Integer bizType, @Param("bizId") Long bizId);
}
