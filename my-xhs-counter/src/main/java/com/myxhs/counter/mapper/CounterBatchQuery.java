package com.myxhs.counter.mapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量查询计数参数（用于 CounterMapper.selectByTargets）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterBatchQuery {

    private int targetType;
    private long targetId;
    private int countType;
}
