package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单快照实体
 * <p>
 * 每次状态变更时记录完整的订单 JSON 快照。
 * 用途：审计回溯、纠纷处理、数据恢复。
 * </p>
 */
@Data
@TableName("t_order_snapshot")
public class OrderSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    /** 用户ID（分片键，冗余存储用于 ShardingSphere 路由） */
    private Long userId;

    /** 触发事件（CREATED/PAID/CANCELLED/DELIVERED/COMPLETED） */
    private String event;

    /** 订单完整快照(JSON) */
    private String snapshotData;

    private LocalDateTime createdAt;
}
