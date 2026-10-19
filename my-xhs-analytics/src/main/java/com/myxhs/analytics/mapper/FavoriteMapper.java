package com.myxhs.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.analytics.entity.Favorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 收藏 Mapper
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /**
     * 根据用户ID和笔记ID删除收藏记录
     */
    @Delete("DELETE FROM t_favorite WHERE user_id = #{userId} AND note_id = #{noteId}")
    int deleteByUserAndNote(@Param("userId") Long userId, @Param("noteId") Long noteId);

    /** C-009 D2-B：收藏列表重建——取用户全部收藏行（含 note_id/created_at） */
    @org.apache.ibatis.annotations.Select("SELECT id, user_id, note_id, created_at FROM t_favorite WHERE user_id = #{userId}")
    java.util.List<Favorite> selectByUserId(@Param("userId") Long userId);
}
