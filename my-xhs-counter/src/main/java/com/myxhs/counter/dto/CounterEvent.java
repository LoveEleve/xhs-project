package com.myxhs.counter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myxhs.common.event.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 计数事件消息体（MQ 传输）
 * <p>
 * 统一的计数变更事件，由各业务服务发送，Counter 服务消费。
 * 支持点赞/收藏/评论/关注等所有计数场景。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CounterEvent extends AbstractDomainEvent<CounterEvent> {

    /** 目标类型：1-笔记 2-用户 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    private Integer countType;

    /** 增量：+1 或 -1 */
    private Long delta;

    @Override
    public String getEventType() {
        return "COUNTER_CHANGED";
    }

    @Override
    public String getSource() {
        return "my-xhs-counter";
    }

    @Override
    @JsonIgnore
    public CounterEvent getPayload() {
        return this;
    }
}
