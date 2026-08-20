package com.myxhs.cart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车事件流水（append-only，可观测性）
 * <p>
 * 消费 CART_TOPIC 追加落库，还原历史加购/改量/勾选/删除时段，不做任何更新/删除。
 * 表：t_cart_event
 * </p>
 */
@Data
@TableName("t_cart_event")
public class CartEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** SKU ID */
    private Long skuId;

    /** 操作类型：ADD / UPDATE / DELETE / CHECK / CHECK_ALL / CLEAR */
    private String action;

    /** 数量 */
    private Integer quantity;

    /** 是否选中 */
    private Integer checked;

    /** 事件时间（生产者时钟） */
    private LocalDateTime eventTime;

    /** MQ 消息 ID（幂等） */
    private String msgId;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
