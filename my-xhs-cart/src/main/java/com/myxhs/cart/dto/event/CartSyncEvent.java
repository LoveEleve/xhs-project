package com.myxhs.cart.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myxhs.common.event.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 购物车同步事件（MQ 异步持久化到 MySQL）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CartSyncEvent extends AbstractDomainEvent<CartSyncEvent> {

    /** 用户ID */
    private Long userId;

    /** SKU ID */
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /** 是否选中 */
    private Integer checked;

    /** 操作类型：ADD / UPDATE / DELETE / CHECK / CHECK_ALL / CLEAR */
    private String action;

    /** 清空屏障时间戳（仅 CLEAR 使用；后续消费者可据此过滤旧条目恢复） */
    private Long clearBarrierTs;

    @Override
    public String getEventType() {
        return "CART_SYNC";
    }

    @Override
    public String getSource() {
        return "my-xhs-cart";
    }

    @Override
    @JsonIgnore
    public CartSyncEvent getPayload() {
        return this;
    }
}
