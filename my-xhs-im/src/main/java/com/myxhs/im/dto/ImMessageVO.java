package com.myxhs.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 消息 VO（对外暴露，替代 Map<String, Object>）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImMessageVO {

    /** 消息ID */
    private Long id;

    /** 发送者ID */
    private Long senderId;

    /** 接收者ID */
    private Long receiverId;

    /** 消息内容 */
    private String content;

    /** 消息类型（C-013 F4 修正：对齐 DDL/ImMessage 口径——0-文本 1-图片 2-系统消息；原"1-文本…4-视频"为臆造口径） */
    private Integer msgType;

    /** 发送时间 */
    private String createdAt;
}
