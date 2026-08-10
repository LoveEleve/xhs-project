package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应（Token 对 + HMAC 密钥）
 */
@Data
@Builder
public class TokenResponse {

    /** Access Token（短期，30分钟） */
    private String accessToken;

    /** Refresh Token（长期，7天） */
    private String refreshToken;

    /** HMAC 签名密钥（per-session，用于非公开接口的请求签名）
     * <p>客户端用此密钥对 method+path+timestamp+nonce 做 HmacSHA256 签名，
     * Gateway 用同一密钥验签。密钥与 Refresh Token 同生命周期（7天）。</p>
     */
    private String hmacSecret;
}
