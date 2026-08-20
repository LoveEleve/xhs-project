package com.myxhs.order.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * SKU 信息（来自 product 服务的 Feign 响应）
 * <p>
 * 轻量 DTO，只含 order 创建时需要的字段，避免引入 product 模块依赖。
 * </p>
 */
@Data
public class SkuInfoDTO {
    private Long id;
    private Long spuId;
    private String name;
    private BigDecimal price;

    /** SKU 主图（继承自所属 SPU 第一张图，来自 product 服务） */
    private String image;

    /** 所属 SPU 状态（T-047：1=上架；order 下单层双状态校验用） */
    private Integer spuStatus;
}
