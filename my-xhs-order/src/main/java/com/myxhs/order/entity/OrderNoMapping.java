package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单号映射表实体（不分片，存储在公共库 my_xhs_order）
 * <p>
 * 用途：通过订单号反查 user_id，解决非分片键查询路由问题。
 * 场景：客服通过订单号查询、支付回调通过订单号定位订单。
 * </p>
 */
@Data
@TableName("t_order_no_mapping")
public class OrderNoMapping implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long orderId;
    private LocalDateTime createdAt;
}
