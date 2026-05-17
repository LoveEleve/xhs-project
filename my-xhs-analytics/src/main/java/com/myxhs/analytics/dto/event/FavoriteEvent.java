package com.myxhs.analytics.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏事件消息体（MQ 传输）
 * <p>
 * 替代原来的 "|" 分隔字符串，使用 JSON 序列化，更健壮、可扩展。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteEvent {

    /** 用户ID */
    private Long userId;

    /** 笔记ID */
    private Long noteId;

    /** 操作类型：FAVORITE / UNFAVORITE */
    private String action;

    /** 收藏时间戳（毫秒） */
    private Long timestamp;
}
