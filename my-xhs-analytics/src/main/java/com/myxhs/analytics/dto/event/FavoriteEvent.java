package com.myxhs.analytics.dto.event;

import com.myxhs.common.event.AbstractDomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode(callSuper = false)
public class FavoriteEvent extends AbstractDomainEvent<FavoriteEvent> {

    /** 用户ID */
    private Long userId;

    /** 笔记ID */
    private Long noteId;

    /** 操作类型：FAVORITE / UNFAVORITE */
    private String action;

    /** 收藏时间戳（毫秒） */
    private Long actionTime;

    @Override
    public String getEventType() {
        return "FAVORITE_EVENT";
    }

    @Override
    public String getSource() {
        return "my-xhs-analytics";
    }

    @Override
    @JsonIgnore
    public FavoriteEvent getPayload() {
        return this;
    }
}
