package com.myxhs.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.content.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 评论 Mapper
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 批量查询各父评论的子评论总数（一次 SQL 替代 N 次 COUNT）
     * <p>
     * 仅对需要精确计数的父评论执行聚合查询。
     * 返回 Map<parentId, count>
     * </p>
     */
    @Select("<script>" +
            "SELECT parent_id, COUNT(*) AS cnt FROM t_comment " +
            "WHERE parent_id IN " +
            "<foreach collection='parentIds' item='id' open='(' close=')' separator=','>" +
            "#{id}" +
            "</foreach>" +
            " AND deleted = 0 GROUP BY parent_id" +
            "</script>")
    Map<Long, Long> batchCountByParentIds(@Param("parentIds") List<Long> parentIds);
}
