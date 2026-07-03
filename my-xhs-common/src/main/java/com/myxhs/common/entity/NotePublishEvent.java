package com.myxhs.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
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
public class NotePublishEvent {

    /** 笔记ID */
    private Long noteId;

    /** 作者ID（对应消费者中的 authorId） */
    private Long authorId;

    /** 发布时间（毫秒时间戳） */
    private Long publishTime;

    /** 笔记类型：0-图文 1-视频 */
    private String noteType;
}
