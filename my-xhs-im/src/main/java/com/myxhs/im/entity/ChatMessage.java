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
 * 聊天消息实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("t_chat_message")
public class ChatMessage {

    @TableId
    private Long id;

    /** 会话ID = min(A,B) << 32 | max(A,B) */
    private Long conversationId;

    private Long senderId;

    private Long receiverId;

    private String content;

    /** 消息类型：0-文本 1-图片 2-系统消息 */
    private Integer msgType;

    /** 会话内消息序列号（Redis INCR 生成，保证同会话消息严格有序） */
    private Long seqNo;

    /** 是否已读：0-未读 1-已读 */
    private Integer isRead;

    private LocalDateTime createdAt;
}
