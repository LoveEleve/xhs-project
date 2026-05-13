package com.myxhs.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 图形验证码 Key */
    @NotBlank(message = "验证码Key不能为空")
    private String captchaKey;

    /** 图形验证码 */
    @NotBlank(message = "验证码不能为空")
    private String captchaCode;
}
