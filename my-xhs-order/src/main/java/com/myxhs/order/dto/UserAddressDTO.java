package com.myxhs.order.dto;

import lombok.Data;

/**
 * 用户收货地址（来自 user 服务 Feign 响应）
 * <p>
 * 订单创建时通过 addressId 获取真实收货地址，写入订单地址快照。
 * </p>
 */
@Data
public class UserAddressDTO {
    private Long id;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detailAddress;
    private Integer isDefault;
}
