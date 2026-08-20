package com.myxhs.common.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类
 * <p>
 * 提供 Token 的生成、解析、校验能力。
 * 使用 HMAC-SHA256 签名算法，密钥长度至少 256 位（32 字节）。
 * </p>
 * <p>
 * 注意：此工具类是无状态的静态方法，Gateway（WebFlux）和业务服务（WebMVC）都可以使用。
 * Token 的存储和黑名单管理由 TokenService 负责（在 my-xhs-user 中实现）。
 * </p>
 */
@Slf4j
public final class JwtUtil {

    private JwtUtil() {
        // 工具类禁止实例化
    }

    /**
     * 生成 Token
     *
     * @param subject    主体（通常是 userId）
     * @param tokenType  Token 类型（access / refresh）
     * @param expireMs   过期时间（毫秒）
     * @param secret     签名密钥（至少 32 字节）
     * @return JWT Token 字符串
     */
    public static String generateToken(String subject, String tokenType, long expireMs, String secret) {
        return generateToken(subject, tokenType, expireMs, secret, null);
    }

    /**
     * 生成 Token（带附加 claim）
     *
     * @param subject    主体（通常是 userId）
     * @param tokenType  Token 类型（access / refresh）
     * @param expireMs   过期时间（毫秒）
     * @param secret     签名密钥（至少 32 字节）
     * @param extraClaims 附加 claim（如 role），可空
     * @return JWT Token 字符串
     */
    public static String generateToken(String subject, String tokenType, long expireMs, String secret,
                                       java.util.Map<String, Object> extraClaims) {
        long now = System.currentTimeMillis();
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        JwtBuilder builder = Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(subject)
                .claim("type", tokenType)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMs))
                .signWith(key);
        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }
        return builder.compact();
    }

    /**
     * 生成 Access Token（30 分钟）
     */
    public static String generateAccessToken(String userId, String secret) {
        return generateToken(userId, "access", 30 * 60 * 1000L, secret);
    }

    /**
     * 生成 Refresh Token（7 天）
     */
    public static String generateRefreshToken(String userId, String secret) {
        return generateToken(userId, "refresh", 7 * 24 * 60 * 60 * 1000L, secret);
    }

    /**
     * 解析 Token
     *
     * @param token  JWT Token 字符串
     * @param secret 签名密钥
     * @return Claims 载荷
     * @throws ExpiredJwtException 如果 Token 已过期
     * @throws JwtException        如果 Token 无效
     */
    public static Claims parseToken(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取 Token 中的用户 ID
     */
    public static String getUserId(String token, String secret) {
        return parseToken(token, secret).getSubject();
    }

    /**
     * 获取 Token 中的 jti（JWT ID，用于黑名单）
     */
    public static String getJti(String token, String secret) {
        return parseToken(token, secret).getId();
    }

    /**
     * 获取 Token 类型（access / refresh）
     */
    public static String getTokenType(String token, String secret) {
        return parseToken(token, secret).get("type", String.class);
    }

    /**
     * 获取 Token 剩余有效期（毫秒）
     *
     * @return 剩余毫秒数，已过期返回 0
     */
    public static long getRemainingMs(String token, String secret) {
        try {
            Date expiration = parseToken(token, secret).getExpiration();
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (ExpiredJwtException e) {
            return 0;
        }
    }

    /**
     * 校验 Token 是否有效（不抛异常）
     */
    public static boolean isValid(String token, String secret) {
        try {
            parseToken(token, secret);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}
