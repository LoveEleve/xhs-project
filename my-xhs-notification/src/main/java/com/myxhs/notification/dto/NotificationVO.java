package com.myxhs.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知 VO（返回给前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {

    private Long id;
    private Integer type;
    private String title;
    private String content;
    private Long senderId;
    private String senderName;
    private String senderAvatar;
    private Long targetId;
    private Integer targetType;
    private Integer isRead;
    private Integer aggregateCount;
    private LocalDateTime createdAt;
}
