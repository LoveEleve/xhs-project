package com.myxhs.common.rpc;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** SKU RPC 传输对象（字段对齐 product SkuVO / cart SkuDTO） */
@Data
public class SkuRpcDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long spuId;
    private String name;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private String specs;
    private Integer status;
    /** 所属 SPU 状态（0-下架 1-上架） */
    private Integer spuStatus;
}
