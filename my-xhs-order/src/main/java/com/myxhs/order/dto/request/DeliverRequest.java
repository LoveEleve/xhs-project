package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发货请求
 */
@Data
public class DeliverRequest {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 物流公司 */
    private String logisticsCompany;

    /** 物流单号 */
    private String trackingNo;
}
