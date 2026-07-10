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

    /** 推送状态：0=未推送 1=推送中 2=已推送 3=推送失败 */
    private Integer pushStatus;

    /** 推送游标（已推送到第几个粉丝） */
    private Integer pushCursor;

    /** 总粉丝数 */
    private Integer pushTotal;
}
