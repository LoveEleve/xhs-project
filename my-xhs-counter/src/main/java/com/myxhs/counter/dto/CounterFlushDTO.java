package com.myxhs.counter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Buffer 刷盘 DTO
 * <p>
 * 用于 Buffer-Trigger 批量写入 DB 时的参数传递。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterFlushDTO {

    /** 雪花 ID（刷盘前由 Service 层赋值） */
    private Long id;

    /** 目标类型 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 计数类型 */
    private Integer countType;

    /** 增量值（可正可负） */
    private Long delta;

    public CounterFlushDTO(Integer targetType, Long targetId, Integer countType, Long delta) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.countType = countType;
        this.delta = delta;
    }
}
