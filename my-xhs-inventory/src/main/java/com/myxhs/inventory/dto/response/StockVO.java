package com.myxhs.inventory.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 库存查询响应
 */
@Data
@Builder
public class StockVO {

    /** SKU ID */
    private Long skuId;

    /** 可用库存（所有桶的总和） */
    private Integer availableStock;

    /** 锁定库存 */
    private Integer lockedStock;

    /** 分桶数 */
    private Integer bucketCount;

    /** 是否已初始化到 Redis */
    private Boolean initialized;
}
