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
     * O-Comment-4 修复（2026-08-13）：每根评论取前 N 条子评论（MySQL 5.7+ 兼容）
     * <p>
     * 旧实现：全局 LIMIT rootIds.size()*maxChildPerParent + ORDER BY id——
     * 当本页根评论的子评论总数超过该上限时，后序根评论的子评论被整体截断（children 空/childCount=0）。
     * 兼容实现：相关子查询统计同 parent_id 下 id <= 当前行的记录数，等价实现“每根前 N 条”，
     * 避免依赖 MySQL 8 的 ROW_NUMBER() 窗口函数。
     * </p>
     */
    @Select("<script>" +
            // A2 性能：ROW_NUMBER 替代相关子查询计数。
            // 实测（5,202 子评论/根）：旧实现单发 >30s 超时 500（O(N²) 索引扫描）；
            // 窗口函数 + idx_parent_deleted_id 后单发毫秒级（见 docs/reports/comment-sql-tuning-20260917.md）。
            "SELECT id, note_id, user_id, parent_id, reply_to_id, content, like_count, deleted, created_at, updated_at FROM (" +
            "  SELECT c.id, c.note_id, c.user_id, c.parent_id, c.reply_to_id, c.content, c.like_count, c.deleted, c.created_at, c.updated_at, " +
            "         ROW_NUMBER() OVER (PARTITION BY c.parent_id ORDER BY c.id) AS rn " +
            "  FROM t_comment c " +
            "  WHERE c.parent_id IN " +
            "<foreach collection='parentIds' item='id' open='(' close=')' separator=','>#{id}</foreach> " +
            "  AND c.deleted = 0 " +
            ") t WHERE t.rn &lt;= #{maxPerParent} ORDER BY t.id" +
            "</script>")
    List<Comment> selectTopChildrenByParentIds(@Param("parentIds") List<Long> parentIds,
                                               @Param("maxPerParent") int maxPerParent);
}
