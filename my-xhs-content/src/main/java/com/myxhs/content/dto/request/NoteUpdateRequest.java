package com.myxhs.content.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 笔记更新请求
 */
@Data
public class NoteUpdateRequest {

    /** 标题（最长128字） */
    @Size(max = 128, message = "标题不能超过128字")
    private String title;

    /** 正文 */
    private String content;

    /** 图片URL列表（最多9张） */
    @Size(max = 9, message = "图片最多9张")
    private List<String> images;

    /** 视频URL */
    private String videoUrl;

    /** 封面图URL */
    private String coverUrl;

    /** 话题ID列表 */
    private List<Long> topicIds;

    /** 标签列表 */
    private List<String> tags;
}
