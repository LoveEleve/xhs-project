package com.myxhs.content.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记列表项响应（不含正文，减少传输量）
 */
@Data
public class NoteItemVO {

    /** 笔记ID */
    private Long id;

    /** 作者ID */
    private Long userId;

    /** 标题 */
    private String title;

    /** 封面图URL */
    private String coverUrl;

    /** 图片URL列表（仅第一张，用于列表缩略图） */
    private String firstImage;

    /** 笔记类型：0-图文 1-视频 */
    private Integer noteType;

    /** 状态：0-草稿 1-审核中 2-已发布 3-已下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
