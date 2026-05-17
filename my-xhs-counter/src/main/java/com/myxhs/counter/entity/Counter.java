package com.myxhs.counter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 计数实体
 * <p>
 * 对应 t_counter 表。
 * 每条记录代表一个目标（笔记/用户）的一种计数（点赞/收藏/评论/粉丝/关注等）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_counter")
public class Counter extends BaseEntity {

    /** 目标类型：1-笔记 2-用户 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    private Integer countType;

    /** 计数值 */
    private Long countValue;
}
