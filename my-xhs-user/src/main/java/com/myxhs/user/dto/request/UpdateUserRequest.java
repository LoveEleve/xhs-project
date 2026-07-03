package com.myxhs.user.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 更新用户信息请求
 */
@Data
public class UpdateUserRequest {

    @Size(max = 64, message = "昵称最长64位")
    private String nickname;

    @Size(max = 512, message = "头像URL过长")
    private String avatar;

    /** 性别：0-未知 1-男 2-女 */
    @Min(value = 0, message = "性别值范围：0-未知 1-男 2-女")
    @Max(value = 2, message = "性别值范围：0-未知 1-男 2-女")
    private Integer gender;

    /** 生日（不能是未来日期） */
    @Past(message = "生日不能是未来日期")
    private LocalDate birthday;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "邮箱格式不正确")
    private String email;

    @Size(max = 256, message = "个性签名最长256位")
    private String signature;
}
