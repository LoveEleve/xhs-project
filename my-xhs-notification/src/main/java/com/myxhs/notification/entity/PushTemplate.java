package com.myxhs.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推送模板实体
 */
@Data
@TableName("t_push_template")
public class PushTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通知类型标识: LIKE/COMMENT/FOLLOW/SYSTEM/ORDER */
    private String type;

    /** 标题模板 */
    private String titleTemplate;

    /** 内容模板 */
    private String contentTemplate;

    /** 聚合标题模板 */
    private String aggregateTitleTemplate;

    /** 状态：0-禁用 1-启用 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
