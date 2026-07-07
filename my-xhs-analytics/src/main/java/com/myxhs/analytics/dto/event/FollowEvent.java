package com.myxhs.analytics.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注事件消息体（MQ 传输）
 * <p>
 * 使用 JSON 序列化，更健壮、可扩展。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowEvent {

    /** 用户ID（关注者） */
    private Long userId;

    /** 目标用户ID（被关注者） */
    private Long targetUserId;

    /** 操作类型：FOLLOW / UNFOLLOW */
    private String action;

    /** 关注时间戳（毫秒） */
    private Long timestamp;
}
