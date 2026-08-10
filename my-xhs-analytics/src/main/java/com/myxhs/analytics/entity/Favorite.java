package com.myxhs.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 收藏实体
 * <p>
 * 对应 t_favorite 表。
 * 注意：收藏关系的权威数据存储在 Redis ZSet 中，MySQL 作为异步落库的持久化兜底。
 * </p>
 */
@Data
@TableName("t_favorite")
public class Favorite implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 笔记ID */
    private Long noteId;

    /** 创建时间 */
    // createdAt依赖DB DEFAULT CURRENT_TIMESTAMP，未继承BaseEntity故无@TableField(fill=INSERT)
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
