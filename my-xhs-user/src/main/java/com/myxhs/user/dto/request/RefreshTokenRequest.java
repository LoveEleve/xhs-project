package com.myxhs.user.dto.request;

import lombok.Data;

/**
 * T-008: refreshToken 请求体传递（避免 query string 进访问日志泄露凭证）
 */
@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
