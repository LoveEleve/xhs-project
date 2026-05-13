package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应（Token 对）
 */
@Data
@Builder
public class TokenResponse {

    /** Access Token（短期，30分钟） */
    private String accessToken;

    /** Refresh Token（长期，7天） */
    private String refreshToken;
}
