package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 售后申请请求
 */
@Data
public class AftersaleApplyRequest {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    @NotNull(message = "SKU ID不能为空")
    private Long skuId;

    /** 1-仅退款 2-退货退款 */
    @NotNull(message = "售后类型不能为空")
    private Integer type;

    /** 申请数量，缺省为整件 */
    private Integer applyQuantity;

    private String reason;

    /** 退货物流单号（type=2 时填写） */
    private String returnWaybill;
}
