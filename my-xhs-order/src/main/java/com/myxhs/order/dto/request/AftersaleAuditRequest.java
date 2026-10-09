package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 售后审核请求（内部调用）
 */
@Data
public class AftersaleAuditRequest {

    @NotBlank(message = "售后单号不能为空")
    private String aftersaleNo;

    /** true-同意（触发退款） false-驳回 */
    @NotNull(message = "审核结论不能为空")
    private Boolean approve;

    /** 驳回原因（approve=false 时必填） */
    private String rejectReason;
}
