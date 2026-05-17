package com.myxhs.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;

/**
 * 更新收货地址请求
 * <p>
 * 只更新非 null 字段，允许部分更新。
 * 空字符串会在 setter 中自动转为 null，避免触发 @Pattern 校验。
 * </p>
 */
@Getter
public class AddressUpdateRequest {

    /** 收货人姓名 */
    @Size(max = 32, message = "收货人姓名最多32个字符")
    private String receiverName;

    /** 收货人手机号（空字符串自动转 null，@Pattern 对 null 不校验） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    /** 省 */
    @Size(max = 32, message = "省份最多32个字符")
    private String province;

    /** 市 */
    @Size(max = 32, message = "城市最多32个字符")
    private String city;

    /** 区 */
    @Size(max = 32, message = "区/县最多32个字符")
    private String district;

    /** 详细地址 */
    @Size(max = 256, message = "详细地址最多256个字符")
    private String detailAddress;

    /** 是否设为默认地址 */
    private Boolean isDefault;

    // ==================== 手写 setter：空字符串自动转 null ====================
    // Jackson @RequestBody 反序列化时会调用 setter，在校验(@Valid)之前生效，
    // 确保前端传 "" 时不会触发 @Pattern 校验失败。

    public void setReceiverName(String receiverName) {
        this.receiverName = blankToNull(receiverName);
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = blankToNull(receiverPhone);
    }

    public void setProvince(String province) {
        this.province = blankToNull(province);
    }

    public void setCity(String city) {
        this.city = blankToNull(city);
    }

    public void setDistrict(String district) {
        this.district = blankToNull(district);
    }

    public void setDetailAddress(String detailAddress) {
        this.detailAddress = blankToNull(detailAddress);
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    private static String blankToNull(String s) {
        return (s != null && s.isBlank()) ? null : s;
    }
}
