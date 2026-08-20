package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 退款回补库存请求（T-071）
 * <p>
 * 全额退款后由 order 服务调用：Redis total/桶 +qty，MQ REFUND_RESTORE 同步 MySQL available。
 * </p>
 */
@Data
public class RefundRestoreRequest {

    @NotNull(message = "orderId不能为空")
    private Long orderId;

    @NotNull(message = "skuId不能为空")
    private Long skuId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量最少为1")
    private Integer quantity;

    /** 路由桶计算用（可空，缺省桶 0） */
    private Long userId;
}
