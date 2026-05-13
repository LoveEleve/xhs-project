package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 图形验证码响应
 */
@Data
@Builder
public class CaptchaResponse {

    /** 验证码 Key（前端回传用于校验） */
    private String captchaKey;

    /** 验证码图片（Base64 编码） */
    private String captchaImage;
}
