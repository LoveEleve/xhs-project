package com.myxhs.ai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 角色解析（RBAC）：管理/内部令牌 → ADMIN；平台 access JWT 的 role claim → 角色映射；其余 VIEWER。
 */
@Slf4j
@Component
public class AiRoleResolver {

    @Value("${myxhs.admin.token:}")
    private String adminToken;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @Value("${JWT_SECRET:}")
    private String jwtSecret;

    public AiRole resolve(String adminCall, String internalCall, String authorization) {
        if (matches(adminToken, adminCall) || matches(internalToken, internalCall)) {
            return AiRole.ADMIN;
        }
        return AiRole.fromClaim(claimRole(authorization));
    }

    public boolean isAdmin(String adminCall, String internalCall, String authorization) {
        return resolve(adminCall, internalCall, authorization) == AiRole.ADMIN;
    }

    public boolean isAdmin(HttpServletRequest request) {
        return isAdmin(request.getHeader("X-Admin-Call"), request.getHeader("X-Internal-Call"),
                request.getHeader("Authorization"));
    }

    /** 解析平台 access JWT 的 role claim（失败按无角色处理） */
    String claimRole(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || jwtSecret == null || jwtSecret.isEmpty()) {
            return null;
        }
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7)).getPayload();
            if (!"access".equals(claims.get("type", String.class))) {
                return null;
            }
            return claims.get("role", String.class);
        } catch (Exception e) {
            log.debug("[RBAC] JWT 解析失败（按 VIEWER）: {}", e.getMessage());
            return null;
        }
    }

    static boolean matches(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
