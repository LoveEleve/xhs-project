package com.myxhs.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 笔记状态事件流水（append-only，可观测性）
 * <p>
 * 记录发布/审核时点（发布即审计通过），用于"内容互动骤降"归因（区分发布少 vs 分发问题）。
 * 表：t_note_event
 * </p>
 */
@Data
@TableName("t_note_event")
public class NoteEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 笔记ID */
    private Long noteId;

    /** 作者ID */
    private Long userId;

    /** 事件类型：PUBLISH / AUDIT_PASS / AUDIT_REJECT / OFFLINE */
    private String eventType;

    /** 事件时笔记状态（2=已发布） */
    private Integer status;

    /** 事件时审核状态（1=通过） */
    private Integer auditStatus;

    /** 事件时间（业务时点） */
    private LocalDateTime eventTime;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
