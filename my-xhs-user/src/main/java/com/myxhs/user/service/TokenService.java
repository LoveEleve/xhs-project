package com.myxhs.user.service;

import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.util.JwtUtil;
import com.myxhs.user.config.JwtProperties;
import com.myxhs.user.dto.response.TokenResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Token 服务
 * <p>
 * 负责 Token 的生成、刷新、注销、黑名单管理。
 * Access Token 存 Redis（用于单设备登录踢出），Refresh Token 同理。
 * 注销时将 Token 的 jti 加入黑名单，黑名单 TTL = Token 剩余有效期。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtProperties jwtProperties;
    private final RedisOperator redisOperator;

    /**
     * 生成 Token 对（Access + Refresh）
     *
     * @param userId 用户 ID
     * @return Token 对
     */
    public TokenResponse generateTokenPair(Long userId) {
        String secret = jwtProperties.getSecret();
        String userIdStr = String.valueOf(userId);

        String accessToken = JwtUtil.generateToken(userIdStr, "access",
                jwtProperties.getAccessTokenExpire(), secret);
        String refreshToken = JwtUtil.generateToken(userIdStr, "refresh",
                jwtProperties.getRefreshTokenExpire(), secret);

        // 存入 Redis（支持后续踢出登录）
        redisOperator.set(
                RedisKeyConstants.USER_TOKEN_ACCESS + userId,
                accessToken,
                jwtProperties.getAccessTokenExpire() / 1000,
                TimeUnit.SECONDS
        );
        redisOperator.set(
                RedisKeyConstants.USER_TOKEN_REFRESH + userId,
                refreshToken,
                jwtProperties.getRefreshTokenExpire() / 1000,
                TimeUnit.SECONDS
        );

        log.info("[Token] 生成 Token 对, userId={}", userId);
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    /**
     * 刷新 Token
     * <p>
     * 校验 Refresh Token 有效性，生成新的 Token 对，旧 Token 加入黑名单。
     * </p>
     *
     * @param refreshToken Refresh Token
     * @return 新的 Token 对
     */
    public TokenResponse refreshToken(String refreshToken) {
        String secret = jwtProperties.getSecret();

        // 1. 一次性解析 Refresh Token，提取所有需要的字段
        Claims claims;
        try {
            claims = JwtUtil.parseToken(refreshToken, secret);
        } catch (Exception e) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }

        String tokenType = claims.get("type", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new BizException(ResultCode.TOKEN_INVALID, "Token 类型错误，需要 Refresh Token");
        }

        // 2. 检查是否在黑名单中
        String jti = claims.getId();
        if (isBlacklisted(jti)) {
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }

        // 3. 将旧 Refresh Token 加入黑名单（直接用已解析的 claims）
        blacklistByClaims(claims);

        // 4. 生成新的 Token 对
        String userId = claims.getSubject();
        return generateTokenPair(Long.parseLong(userId));
    }

    /**
     * 注销（将 Access Token 和 Refresh Token 都加入黑名单）
     */
    public void logout(String accessToken, String refreshToken) {
        blacklistToken(accessToken);
        if (refreshToken != null) {
            blacklistToken(refreshToken);
        }

        // 清除 Redis 中存储的 Token
        try {
            String secret = jwtProperties.getSecret();
            String userId = JwtUtil.getUserId(accessToken, secret);
            redisOperator.delete(RedisKeyConstants.USER_TOKEN_ACCESS + userId);
            redisOperator.delete(RedisKeyConstants.USER_TOKEN_REFRESH + userId);
        } catch (Exception e) {
            log.warn("[Token] 清除 Redis Token 失败", e);
        }

        log.info("[Token] 用户注销成功");
    }

    /**
     * 将 Token 加入黑名单（解析 Token 获取 jti 和过期时间）
     */
    private void blacklistToken(String token) {
        try {
            String secret = jwtProperties.getSecret();
            Claims claims = JwtUtil.parseToken(token, secret);
            blacklistByClaims(claims);
        } catch (Exception e) {
            log.warn("[Token] 加入黑名单失败: {}", e.getMessage());
        }
    }

    /**
     * 根据已解析的 Claims 加入黑名单（避免重复解析 Token）
     */
    private void blacklistByClaims(Claims claims) {
        String jti = claims.getId();
        Date expiration = claims.getExpiration();
        long remainingMs = expiration.getTime() - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisOperator.set(
                    RedisKeyConstants.USER_TOKEN_BLACKLIST + jti,
                    "1",
                    remainingMs / 1000 + 1,
                    TimeUnit.SECONDS
            );
        }
    }

    /**
     * 检查 Token 是否在黑名单中
     */
    public boolean isBlacklisted(String jti) {
        Object val = redisOperator.get(RedisKeyConstants.USER_TOKEN_BLACKLIST + jti);
        return val != null;
    }
}
