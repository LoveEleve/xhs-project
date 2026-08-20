package com.myxhs.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品浏览事件流水（append-only，可观测性）
 * <p>
 * 详情页单条浏览埋点（批量/缓存异步刷新路径不记录），用于漏斗分析（商品浏览→加购→下单→支付）。
 * 表：t_product_behavior
 * </p>
 */
@Data
@TableName("t_product_behavior")
public class ProductBehavior implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID（未登录=0） */
    private Long userId;

    /** SPU ID */
    private Long spuId;

    /** 浏览目标 SKU（详情页首 SKU，可为空） */
    private Long skuId;

    /** 行为类型：1-浏览 */
    private Integer behaviorType;

    /** 事件时间 */
    private LocalDateTime eventTime;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
