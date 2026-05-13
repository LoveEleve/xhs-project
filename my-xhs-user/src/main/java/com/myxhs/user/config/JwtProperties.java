package com.myxhs.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥（至少 32 字节） */
    private String secret;

    /** Access Token 过期时间（毫秒），默认 30 分钟 */
    private long accessTokenExpire = 1800000;

    /** Refresh Token 过期时间（毫秒），默认 7 天 */
    private long refreshTokenExpire = 604800000;
}
