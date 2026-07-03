package com.myxhs.content.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表（发送端可靠性保障）
 * <p>
 * 与笔记记录在同一事务中写入，事务提交后异步发送 MQ。
 * MQ 发送成功时标记为已发送，失败时由定时任务扫描重试。
 * 参考 order 模块的 LocalMessage 设计。
 * </p>
 */
@Data
@TableName("t_local_message")
public class LocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** MQ Topic */
    private String topic;

    /** 消息体（JSON） */
    private String body;

    /** 状态：0=待发送 1=已发送 2=发送失败 3=死信 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
