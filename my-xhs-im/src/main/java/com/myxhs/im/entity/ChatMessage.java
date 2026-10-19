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

    /** 会话ID（C-013 F4 修正：首条消息分配雪花 ID 并写入 t_chat_user_relation，双方复用；勿用哈希拼接） */
    private Long conversationId;

    private Long senderId;

    private Long receiverId;

    private String content;

    /** 消息类型：0-文本 1-图片 2-系统消息 */
    private Integer msgType;

    /** 会话内消息序列号（Redis INCR 生成，保证同会话消息严格有序） */
    private Long seqNo;

    // C-013 F5：原 is_read 字段（0-未读 1-已读）零读写（已读态以 t_chat_user_relation.unread_count 表达）
    // → 实体字段移除（DDL 列保留兼容，勿新增引用）

    private LocalDateTime createdAt;
}
