package com.myxhs.content.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 笔记发布/编辑请求
 */
@Data
public class NotePublishRequest {

    /** 标题（必填，最长128字） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题不能超过128字")
    private String title;

    /** 正文（最长20000字） */
    @Size(max = 20000, message = "正文不能超过20000字")
    private String content;

    /** 图片URL列表（最多9张） */
    @Size(max = 9, message = "图片最多9张")
    private List<String> images;

    /** 视频URL */
    @Size(max = 512, message = "视频URL最长512字符")
    private String videoUrl;

    /** 封面图URL */
    @Size(max = 512, message = "封面URL最长512字符")
    private String coverUrl;

    /** 话题ID列表 */
    private List<Long> topicIds;

    /** 标签列表 */
    private List<String> tags;

    /** 笔记类型：0-图文 1-视频，默认0 */
    @Min(value = 0, message = "笔记类型不合法")
    @Max(value = 1, message = "笔记类型不合法")
    private Integer noteType = 0;
}
