package com.myxhs.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 鉴权白名单配置
 * <p>
 * 白名单中的路径不需要 JWT 鉴权，直接放行。
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.auth")
public class AuthProperties {

    /**
     * JWT 签名密钥（与 my-xhs-user 的 jwt.secret 保持一致）
     */
    private String secret;

    /**
     * HMAC 总开关。
     * <p>
     * 默认关闭：外部用户主路径仅保留 JWT，HMAC 降级为可选增强。
     * 如需恢复高敏接口签名校验，可在配置中显式开启。
     */
    private boolean hmacEnabled = false;

    /**
     * HMAC-SHA256 签名密钥（用于防篡改+防重放校验）
     * <p>
     * 与 JWT 密钥分离，职责不同：
     * - JWT 密钥：身份认证（谁在请求）
     * - HMAC 密钥：请求完整性（请求是否被篡改/重放）
     */
    private String hmacSecret;

    /**
     * 不需要鉴权的路径列表（支持 Ant 风格匹配）
     */
    private List<String> whiteList = new ArrayList<>();

    /**
     * 不需要 HMAC 签名校验的路径列表（支持 Ant 风格匹配）
     * <p>
     * JWT 鉴权白名单和 HMAC 签名白名单是两个独立的安全维度：
     * - JWT 白名单：不需要身份认证的公开接口（登录/注册等）
     * - HMAC 白名单：不需要签名校验的接口（公开读接口通常不需要防篡改）
     * <p>
     * 安全原则：
     * - 不携带 X-Timestamp 的请求，必须走 HMAC 白名单判断，而非直接放行
     * - 携带了 X-Timestamp 但不在白名单中的请求，必须完成完整的签名校验
     * - 登录/注册等公开接口不需要签名（无敏感数据，且客户端可能未实现签名）
     * - 写接口（下单/支付等）必须签名，防止参数篡改
     */
    private List<String> hmacWhiteList = new ArrayList<>();
}
