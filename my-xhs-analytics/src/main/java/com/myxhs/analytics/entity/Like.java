package com.myxhs.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞实体
 * <p>
 * 对应 t_like 表。
 * 注意：点赞关系的权威数据存储在 Redis Set 中，MySQL 作为异步落库的持久化兜底。
 * </p>
 */
@Data
@TableName("t_like")
public class Like implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 业务类型：1-笔记 2-评论 */
    private Integer bizType;

    /** 业务ID（笔记ID或评论ID） */
    private Long bizId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
