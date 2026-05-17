package com.myxhs.content.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记详情响应
 */
@Data
public class NoteDetailVO {

    /** 笔记ID */
    private Long id;

    /** 作者ID */
    private Long userId;

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;

    /** 图片URL列表 */
    private List<String> images;

    /** 视频URL */
    private String videoUrl;

    /** 封面图URL */
    private String coverUrl;

    /** 话题ID列表 */
    private List<Long> topicIds;

    /** 标签列表 */
    private List<String> tags;

    /** 状态：0-草稿 1-审核中 2-已发布 3-已下架 */
    private Integer status;

    /** 状态描述 */
    private String statusDesc;

    /** 笔记类型：0-图文 1-视频 */
    private Integer noteType;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
