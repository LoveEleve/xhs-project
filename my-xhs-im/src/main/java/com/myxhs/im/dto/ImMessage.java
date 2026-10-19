package com.myxhs.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 消息协议（客户端 → 服务端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImMessage {

    /** 协议版本号（向下兼容） */
    private Integer ver;

    /** 消息类型：CHAT / ACK / READ / TYPING / PING / LOGOUT */
    private String type;

    /** 接收者ID（CHAT 时必填） */
    private Long to;

    /** 对方ID（READ / TYPING 时使用） */
    private Long peerId;

    /** 消息内容 */
    private String content;

    /** 消息类型：0-文本 1-图片 2-系统消息 */
    private Integer msgType;

    /** 消息ID（ACK / READ 时使用） */
    private Long msgId;

    /** 链路追踪ID（可选）：客户端可透传，缺省时使用握手阶段生成/HTTP头带入的值 */
    private String traceId;

    /**
     * 客户端消息幂等键（C-013 F6，可选）：同一 clientMsgId 重发（ACK 丢失后的客户端重试）会被去重，
     * 直接 ACK 原 msgId 而不重复落库/路由；不传则保持旧行为（无幂等）。
     */
    private String clientMsgId;
}
