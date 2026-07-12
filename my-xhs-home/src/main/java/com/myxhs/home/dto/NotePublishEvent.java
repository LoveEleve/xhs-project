package com.myxhs.home.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myxhs.common.event.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 笔记发布事件（MQ 消息体）
 * <p>
 * 由 content 服务发布笔记后发送到 FEED_TOPIC，
 * home 服务消费后推送到粉丝收件箱（推模式）或写入作者发件箱（拉模式）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotePublishEvent extends AbstractDomainEvent<NotePublishEvent> {

    /** 笔记ID */
    private Long noteId;

    /** 作者ID */
    private Long authorId;

    /** 发布时间戳（毫秒） */
    private Long publishTime;

    @Override
    public String getEventType() {
        return "NOTE_PUBLISHED";
    }

    @Override
    public String getSource() {
        return "my-xhs-content";
    }

    @Override
    @JsonIgnore
    public NotePublishEvent getPayload() {
        return this;
    }
}
