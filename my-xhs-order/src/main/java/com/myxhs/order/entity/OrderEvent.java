package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 订单事件实体（Event Sourcing 不可变事件）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_event")
public class OrderEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private String eventType;
    private Integer fromStatus;
    private Integer toStatus;
    private String payload;
    private Integer eventSeq;
    private LocalDateTime eventTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
