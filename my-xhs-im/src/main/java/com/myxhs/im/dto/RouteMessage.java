package com.myxhs.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨实例消息路由对象（通过 RocketMQ 传递）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteMessage {

    /** 目标用户ID */
    private Long receiverId;

    /** 目标实例 serverId */
    private String targetServerId;

    /** 消息ID */
    private Long msgId;

    /** 发送者ID */
    private Long senderId;

    /** 消息内容 */
    private String content;

    /** 消息类型 */
    private Integer msgType;

    /** 时间戳 */
    private Long timestamp;
}
