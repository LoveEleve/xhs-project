package com.myxhs.coupon.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建优惠券模板请求
 */
@Data
public class CreateTemplateRequest {

    @NotBlank(message = "优惠券名称不能为空")
    @Size(max = 128, message = "名称最长128字符")
    private String name;

    /** 类型：1-满减 2-折扣 3-无门槛 */
    @NotNull(message = "类型不能为空")
    @Min(value = 1, message = "类型值范围1-3")
    @Max(value = 3, message = "类型值范围1-3")
    private Integer type;

    /** 优惠金额/折扣率 */
    @NotNull(message = "优惠值不能为空")
    @DecimalMin(value = "0.01", message = "优惠值必须大于0")
    private BigDecimal discountValue;

    /** 最低消费金额（可为0表示无门槛） */
    @DecimalMin(value = "0.00", message = "最低消费金额不能为负数")
    private BigDecimal minAmount;

    /** 发放总量 */
    @NotNull(message = "发放总量不能为空")
    @Min(value = 1, message = "发放总量至少为1")
    private Integer totalCount;

    /** 每人限领 */
    @NotNull(message = "每人限领不能为空")
    @Min(value = 1, message = "每人限领至少为1")
    @Max(value = 10, message = "每人限领最多10张")
    private Integer perUserLimit;

    /** 有效期开始 */
    @NotNull(message = "有效期开始时间不能为空")
    @FutureOrPresent(message = "有效期开始不能为过去时间")
    private LocalDateTime validStart;

    /** 有效期结束 */
    @NotNull(message = "有效期结束时间不能为空")
    @FutureOrPresent(message = "有效期结束不能为过去时间")
    private LocalDateTime validEnd;
}
