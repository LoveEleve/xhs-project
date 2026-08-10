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
 * 关注关系实体
 * <p>
 * 对应 t_follow 表。
 * 注意：关注关系的权威数据存储在 Redis ZSet 中，MySQL 作为异步落库的持久化兜底。
 * </p>
 */
@Data
@TableName("t_follow")
public class Follow implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID（关注者） */
    private Long userId;

    /** 被关注用户ID */
    private Long followUserId;

    /** 关注时间 */
    // createdAt依赖DB DEFAULT CURRENT_TIMESTAMP，未继承BaseEntity故无@TableField(fill=INSERT)
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
