package com.myxhs.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 通知实体
 */
@Data
@Accessors(chain = true)
@TableName("t_notification")
public class Notification {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 接收用户ID */
    private Long userId;

    /** 通知类型：1-点赞 2-评论 3-关注 4-系统通知 5-订单通知 */
    private Integer type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 发送者ID */
    private Long senderId;

    /** 发送者昵称(冗余) */
    private String senderName;

    /** 发送者头像(冗余) */
    private String senderAvatar;

    /** 关联目标ID(笔记/商品/订单) */
    private Long targetId;

    /** 目标类型：1-笔记 2-商品 3-订单 */
    private Integer targetType;

    /** 是否已读：0-未读 1-已读 */
    private Integer isRead;

    /** 聚合数量 */
    private Integer aggregateCount;

    /** 是否已聚合：0-否 1-是 */
    private Integer isAggregated;

    /** 聚合组ID */
    private Long aggregateId;

    /** 通知日期(虚拟列，由created_at派生) */
    private LocalDateTime notifyDate;

    /** 扩展数据JSON */
    private String extraData;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
