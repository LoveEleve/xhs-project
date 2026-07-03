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

    /** 消息类型：1-文本 2-图片 3-语音 4-视频 */
    private Integer msgType;

    /** 发送时间 */
    private String createdAt;
}
