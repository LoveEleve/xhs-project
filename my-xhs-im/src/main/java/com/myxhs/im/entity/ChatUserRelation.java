package com.myxhs.im.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户会话关系实体
 * <p>
 * 每对用户双方各一条记录（A→B 和 B→A），
 * 用于展示会话列表（最后一条消息、未读数等）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("t_chat_user_relation")
public class ChatUserRelation {

    @TableId
    private Long id;

    private Long userId;

    private Long peerId;

    private Long conversationId;

    private Long lastMessageId;

    private String lastContent;

    private Integer lastMsgType;

    private Integer unreadCount;

    /** 是否删除会话：0-否 1-是 */
    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
