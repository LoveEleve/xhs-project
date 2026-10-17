package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发货请求（ITER-001 拆单：按子订单号发货）
 */
@Data
public class DeliverRequest {

    @NotBlank(message = "子订单号不能为空")
    private String subOrderNo;

    /** 物流公司 */
    private String logisticsCompany;

    /** 物流单号 */
    private String trackingNo;
}
