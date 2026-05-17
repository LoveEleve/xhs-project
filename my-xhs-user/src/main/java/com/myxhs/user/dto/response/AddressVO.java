package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收货地址 VO（手机号脱敏）
 */
@Data
@Builder
public class AddressVO {

    /** 地址ID */
    private Long id;

    /** 收货人姓名 */
    private String receiverName;

    /** 收货人手机号（脱敏：138****1234） */
    private String receiverPhone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区 */
    private String district;

    /** 详细地址 */
    private String detailAddress;

    /** 是否默认：0-否 1-是 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
