package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表实体
 * <p>
 * 与 t_order 同库，保证本地事务原子性。
 * 定时任务扫描 status=0（待处理）的消息进行补发。
 * </p>
 */
@Data
@TableName("t_local_message")
public class LocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID（分片键，冗余存储用于 ShardingSphere 路由） */
    private Long userId;

    /** 事务ID（订单号） */
    private String transactionId;

    /** 服务名 */
    private String serviceName;

    /** 操作类型 */
    private String operationType;

    /** 操作参数JSON */
    private String payload;

    /** 状态：0-待处理 1-成功 2-失败 3-死信 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
