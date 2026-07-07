package com.myxhs.analytics.dto.event;

import com.myxhs.common.event.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 点赞事件消息体（MQ 传输）
 * <p>
 * 替代原来的 "|" 分隔字符串，使用 JSON 序列化，更健壮、可扩展。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class LikeEvent extends AbstractDomainEvent<LikeEvent> {

    /** 用户ID */
    private Long userId;

    /** 业务类型：1-笔记 2-评论 */
    private Integer bizType;

    /** 业务ID */
    private Long bizId;

    /** 操作类型：LIKE / UNLIKE */
    private String action;

    @Override
    public String getEventType() {
        return "LIKE_EVENT";
    }

    @Override
    public String getSource() {
        return "my-xhs-analytics";
    }

    @Override
    public LikeEvent getPayload() {
        return this;
    }
}
