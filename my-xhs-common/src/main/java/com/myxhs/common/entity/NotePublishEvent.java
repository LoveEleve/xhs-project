package com.myxhs.common.entity;

import com.myxhs.common.event.AbstractDomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 笔记发布事件 DTO（MQ 消息体）
 * <p>
 * 由 NoteService 在笔记发布成功后发送到 FEED_TOPIC，
 * 由 FeedPushConsumer（my-xhs-home）消费。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotePublishEvent extends AbstractDomainEvent<NotePublishEvent> {

    /** 笔记ID */
    private Long noteId;

    /** 作者ID（对应消费者中的 authorId） */
    private Long authorId;

    /** 发布时间（毫秒时间戳） */
    private Long publishTime;

    /** 笔记类型：0-图文 1-视频 */
    private String noteType;

    /** 本地消息表 ID（用于 FeedPushConsumer 更新推送进度，支持断点续推） */
    private Long localMsgId;

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
