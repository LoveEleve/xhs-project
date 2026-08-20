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
            "SELECT parent_id AS pid, COUNT(*) AS cnt FROM t_comment " +
            "WHERE parent_id IN " +
            "<foreach collection='parentIds' item='id' open='(' close=')' separator=','>" +
            "#{id}" +
            "</foreach>" +
            " AND deleted = 0 GROUP BY parent_id" +
            "</script>")
    List<Map<String, Object>> batchCountByParentIds(@Param("parentIds") List<Long> parentIds);

    /**
     * O-Comment-4 修复（2026-08-13）：每根评论取前 N 条子评论（窗口函数按 parent 分区）
     * <p>
     * 旧实现：全局 LIMIT rootIds.size()*maxChildPerParent + ORDER BY id——
     * 当本页根评论的子评论总数超过该上限时，后序根评论的子评论被整体截断（children 空/childCount=0）。
     * 新实现：ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY id) 每根独立取前 maxChildPerParent 条，无全局截断。
     * </p>
     */
    @Select("<script>" +
            "SELECT id, note_id, user_id, parent_id, reply_to_id, content, like_count, deleted, created_at, updated_at FROM (" +
            "  SELECT id, note_id, user_id, parent_id, reply_to_id, content, like_count, deleted, created_at, updated_at, " +
            "         ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY id) AS rn " +
            "  FROM t_comment " +
            "  WHERE parent_id IN " +
            "  <foreach collection='parentIds' item='id' open='(' close=')' separator=','>#{id}</foreach> " +
            "  AND deleted = 0" +
            ") sub WHERE rn &lt;= #{maxPerParent} " +
            "ORDER BY id" +
            "</script>")
    List<Comment> selectTopChildrenByParentIds(@Param("parentIds") List<Long> parentIds,
                                               @Param("maxPerParent") int maxPerParent);
}
