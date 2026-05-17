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
}
