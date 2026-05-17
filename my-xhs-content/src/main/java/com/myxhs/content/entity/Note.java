package com.myxhs.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 笔记实体
 * <p>
 * 对应 t_note 表。images/topicIds/tags 以 JSON 字符串存储，
 * 业务层通过 JSON 序列化/反序列化转换为 List。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_note")
public class Note extends BaseEntity {

    /** 作者ID */
    private Long userId;

    /** 标题（最长128字） */
    private String title;

    /** 正文 */
    private String content;

    /** 图片URL列表(JSON数组) */
    private String images;

    /** 视频URL */
    private String videoUrl;

    /** 封面图URL */
    private String coverUrl;

    /** 话题ID列表(JSON数组) */
    private String topicIds;

    /** 标签列表(JSON数组) */
    private String tags;

    /** 状态：0-草稿 1-审核中 2-已发布 3-已下架 */
    private Integer status;

    /** 审核状态：0-待审核 1-通过 2-拒绝 */
    private Integer auditStatus;

    /** 审核拒绝原因 */
    private String rejectReason;

    /** 笔记类型：0-图文 1-视频 */
    private Integer noteType;
}
