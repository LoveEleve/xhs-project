package com.myxhs.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增收货地址请求
 */
@Data
public class AddressCreateRequest {

    /** 收货人姓名 */
    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 32, message = "收货人姓名最多32个字符")
    private String receiverName;

    /** 收货人手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    /** 省 */
    @NotBlank(message = "省份不能为空")
    @Size(max = 32, message = "省份最多32个字符")
    private String province;

    /** 市 */
    @NotBlank(message = "城市不能为空")
    @Size(max = 32, message = "城市最多32个字符")
    private String city;

    /** 区 */
    @NotBlank(message = "区/县不能为空")
    @Size(max = 32, message = "区/县最多32个字符")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 256, message = "详细地址最多256个字符")
    private String detailAddress;

    /** 是否设为默认地址 */
    private Boolean isDefault;
}
