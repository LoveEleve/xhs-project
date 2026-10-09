package com.myxhs.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.im.entity.ChatUserRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatUserRelationMapper extends BaseMapper<ChatUserRelation> {

    /**
     * 定向更新会话（新消息路径）
     * <p>
     * 原实现 select + updateById 整行覆盖：并发消息丢未读（读改写丢更新）、
     * 并发已读回执把 last_message 回退成旧快照。此处只改消息相关列，
     * unread_count 用 SQL 原子自增。
     * </p>
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_chat_user_relation SET "
            + "last_message_id = #{msgId}, last_content = #{content}, last_msg_type = #{msgType}, "
            + "unread_count = unread_count + #{unreadIncrement}, is_deleted = 0, updated_at = #{updatedAt} "
            + "WHERE user_id = #{userId} AND peer_id = #{peerId}")
    int updateOnNewMessage(@Param("userId") Long userId, @Param("peerId") Long peerId,
                           @Param("msgId") long msgId, @Param("content") String content,
                           @Param("msgType") int msgType, @Param("unreadIncrement") int unreadIncrement,
                           @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 删除会话（软删）：is_deleted=1；对方后续发新消息时会话自动复活（upsert 置 0）
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_chat_user_relation SET is_deleted = 1 "
            + "WHERE user_id = #{userId} AND peer_id = #{peerId} AND is_deleted = 0")
    int softDeleteConversation(@Param("userId") Long userId, @Param("peerId") Long peerId);

    /**
     * 定向清零未读（已读回执路径）：只改 unread_count，不整行覆盖
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_chat_user_relation SET unread_count = 0 "
            + "WHERE user_id = #{userId} AND peer_id = #{peerId} AND unread_count > 0")
    int resetUnread(@Param("userId") Long userId, @Param("peerId") Long peerId);
}
