package com.myxhs.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨实例消息路由对象（通过 Redis Pub/Sub 传递）
 * <p>
 * 【M4 改造】传输方式从 RocketMQ BROADCASTING 改为 Redis Pub/Sub 定向投递。
 * msgType 约定：0=普通聊天 98=TYPING 99=已读回执
 * msgType ∈ {98, 99} 时 content 字段为完整 JSON 字符串，直接透传。
 * </p>
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

    /** 会话内序列号（M8新增，保证跨实例消息顺序一致性） */
    private Long seqNo;

    /** 发送者ID */
    private Long senderId;

    /** 消息内容 */
    private String content;

    /** 消息类型 */
    private Integer msgType;

    /** 时间戳 */
    private Long timestamp;
}
