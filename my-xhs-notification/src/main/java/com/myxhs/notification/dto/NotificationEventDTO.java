package com.myxhs.notification.dto;

import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 社交事件 DTO（MQ 消息体）
 * <p>
 * 由 social/content/order 服务发送到 NOTIFICATION_TOPIC。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventDTO {

    /** 事件类型：1-点赞 2-评论 3-关注 4-系统通知 5-订单通知 */
    @NotNull(message = "事件类型不能为空")
    private Integer type;

    /** 发送者ID */
    private Long senderId;

    /** 发送者昵称 */
    private String senderName;

    /** 发送者头像 */
    private String senderAvatar;

    /** 接收者ID */
    @NotNull(message = "接收者ID不能为空")
    private Long targetUserId;

    /** 关联目标ID（笔记ID/商品ID/订单ID） */
    private Long targetId;

    /** 目标类型：1-笔记 2-商品 3-订单 */
    private Integer targetType;

    /** 目标名称（笔记标题等，用于模板渲染） */
    private String targetName;

    /** 内容（评论内容等） */
    private String content;

    /** 扩展数据JSON */
    private String extraData;
}
