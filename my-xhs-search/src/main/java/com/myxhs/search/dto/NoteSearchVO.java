package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 笔记搜索结果项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSearchVO {

    private Long noteId;
    private Long userId;
    private String title;
    private String content;
    private String coverImage;
    private Long likeCount;
    private Long collectCount;
    private Long commentCount;
    private String createdAt;

    /** 高亮标题（包含 <em> 标签） */
    private String highlightTitle;

    /** 高亮内容（包含 <em> 标签） */
    private String highlightContent;
}
