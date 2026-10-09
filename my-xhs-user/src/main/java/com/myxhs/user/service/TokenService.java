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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
    private final RedissonClient redissonClient;
    /** T-005: 刷新时校验用户状态（禁用账号禁止续期） */
    private final com.myxhs.user.mapper.UserMapper userMapper;

    /**
     * 生成 Token 对（Access + Refresh）
     * <p>
     * 【设计决策：单设备登录】
     * Redis 中以 userId 为 Key 存储 Token，后登录会覆盖前一个 Token。
     * 这意味着同一用户同时只能有一个有效的 Token 对（即单设备登录）。
     * 如果后续需要支持多端登录，需要将 Key 改为 {userId}:{deviceId} 格式，
     * 并在登录时传入设备标识。
     * </p>
     *
     * @param userId 用户 ID
     * @return Token 对
     */
    public TokenResponse generateTokenPair(Long userId) {
        return generateTokenPair(userId, null);
    }

    /**
     * 生成 Token 对（带角色 claim，Gateway 集成 2026-08-17）
     * <p>
     * role 写入 JWT claim：gateway（WebFlux 无 DB）从 token 读取并注入 X-User-Role，
     * 保持网关无状态。role 变更后需重新登录生效（存量 token 全过期场景无影响）。
     * </p>
     *
     * @param userId 用户 ID
     * @param role   用户角色（OPERATOR/TECH），空则不带 claim
     * @return Token 对
     */
    public TokenResponse generateTokenPair(Long userId, String role) {
        String secret = jwtProperties.getSecret();
        String userIdStr = String.valueOf(userId);

        java.util.Map<String, Object> extra = role == null || role.isBlank()
                ? null : java.util.Map.of("role", role);
        String accessToken = JwtUtil.generateToken(userIdStr, "access",
                jwtProperties.getAccessTokenExpire(), secret, extra);
        String refreshToken = JwtUtil.generateToken(userIdStr, "refresh",
                jwtProperties.getRefreshTokenExpire(), secret, extra);

        // 将旧 access token 加入黑名单（单设备登录：新登录踢出旧设备）
        String oldAccessToken = redisOperator.getString(RedisKeyConstants.USER_TOKEN_ACCESS + userId);
        if (oldAccessToken != null && !oldAccessToken.isEmpty()) {
            blacklistOldToken(oldAccessToken);
        }

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

        // 生成 per-session HMAC 密钥（用于非公开接口的请求签名，防篡改+防重放）
        // 密钥与 Refresh Token 同生命周期（7天），每次登录重新生成（旧密钥覆盖）
        String hmacSecret = java.util.UUID.randomUUID().toString().replace("-", "");
        redisOperator.set(
                RedisKeyConstants.USER_HMAC_SECRET + userId,
                hmacSecret,
                jwtProperties.getRefreshTokenExpire() / 1000,
                TimeUnit.SECONDS
        );

        log.info("[Token] 生成 Token 对, userId={}", userId);
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .hmacSecret(hmacSecret)
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
            log.info("[Token] 刷新失败, Token 解析异常: {}", e.getMessage());
            throw new BizException(ResultCode.TOKEN_INVALID);
        }

        String tokenType = claims.get("type", String.class);
        if (!"refresh".equals(tokenType)) {
            log.info("[Token] 刷新失败, Token 类型错误: type={}, userId={}", tokenType, claims.getSubject());
            throw new BizException(ResultCode.TOKEN_INVALID, "Token 类型错误，需要 Refresh Token");
        }

        // 2. 检查是否在黑名单中
        String jti = claims.getId();
        if (isBlacklisted(jti)) {
            log.info("[Token] 刷新失败, Token 已在黑名单中, jti={}, userId={}", jti, claims.getSubject());
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }

        // 3. 分布式锁防止并发刷新（多个请求同时发现 AccessToken 过期，都来刷新）
        //    锁粒度：按 jti（每个 RefreshToken 唯一），不影响其他用户
        String lockKey = RedisKeyConstants.TOKEN_REFRESH_LOCK + jti;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                // 获取锁失败，说明其他线程正在刷新，返回友好提示
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "Token 正在刷新中，请稍后重试");
            }

            // 4. 二次检查黑名单（可能其他线程已经刷新并将旧 Token 加入黑名单）
            if (isBlacklisted(jti)) {
                log.info("[Token] 刷新失败(二次检查), Token 已被其他线程刷新, jti={}, userId={}", jti, claims.getSubject());
                throw new BizException(ResultCode.TOKEN_REVOKED, "Token 已被刷新，请使用新 Token");
            }

            // 5. 校验 Redis 中存储的 Refresh Token 是否与传入的一致（单设备登录保障）
            //    如果用户在其他设备登录，Redis 中的 Token 会被覆盖，旧设备的 Token 应失效
            String userId = claims.getSubject();
            Object storedRefreshToken = redisOperator.get(RedisKeyConstants.USER_TOKEN_REFRESH + userId);
            if (storedRefreshToken == null || !refreshToken.equals(storedRefreshToken.toString())) {
                log.info("[Token] 刷新失败, Token 已被其他设备覆盖, userId={}, jti={}", userId, jti);
                throw new BizException(ResultCode.TOKEN_REVOKED, "Token 已被其他设备覆盖，请重新登录");
            }

            // 5.5 T-005: 校验用户状态——禁用/逻辑删除账号禁止续期（封号即失效）
            Long uid = Long.valueOf(claims.getSubject());
            com.myxhs.user.entity.User u = userMapper.selectById(uid);
            if (u == null || u.getStatus() == null || u.getStatus() != 1) {
                log.info("[Token] 刷新失败, 账号不可用(禁用/删除), userId={}", uid);
                throw new BizException(ResultCode.ACCOUNT_DISABLED);
            }

            // 6. 将旧 Refresh Token 加入黑名单（直接用已解析的 claims）
            blacklistByClaims(claims);

            // 7. 生成新的 Token 对
            log.info("[Token] 刷新成功, userId={}, 旧jti={}", userId, jti);
            return generateTokenPair(Long.parseLong(userId), u.getRole());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "Token 刷新被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 注销（将 Access Token 和 Refresh Token 都加入黑名单）
     */
    public void logout(String accessToken, String refreshToken) {
        // RV30：允许 access 为空（refresh-only 注销）
        if (accessToken != null && !accessToken.isBlank()) {
            blacklistToken(accessToken);
        }
        if (refreshToken != null) {
            blacklistToken(refreshToken);
        }

        // 清除 Redis 中存储的 Token
        // T-007: access 缺失时用 refreshToken 兜底解析 userId（否则旧 access 30min 内仍有效）
        String userId = null;
        try {
            String secret = jwtProperties.getSecret();
            try {
                userId = JwtUtil.getUserId(accessToken, secret);
            } catch (Exception e) {
                if (refreshToken != null) {
                    Claims claims = JwtUtil.parseToken(refreshToken, secret);
                    userId = claims.getSubject();
                    // access 缺失：黑名单兜底由 blacklistToken 完成（refresh 已入黑名单）；
                    // 旧 access 无法按 jti 拉黑，但可清 Redis 使单设备校验失效（刷新时被拒）
                }
            }
            if (userId != null) {
                redisOperator.delete(RedisKeyConstants.USER_TOKEN_ACCESS + userId);
                redisOperator.delete(RedisKeyConstants.USER_TOKEN_REFRESH + userId);
                redisOperator.delete(RedisKeyConstants.USER_HMAC_SECRET + userId);
            }
        } catch (Exception e) {
            log.warn("[Token] 清除 Redis Token 失败", e);
        }

        log.info("[Token] 用户注销成功, userId={}", userId);
    }

    /**
     * 注销指定用户的所有活跃 Token（修改密码/账号冻结等场景）
     * <p>
     * 从 Redis 中取出当前活跃的 access/refresh token，加入黑名单后删除映射。
     * </p>
     */
    public void revokeAllTokens(Long userId) {
        String accessTokenKey = RedisKeyConstants.USER_TOKEN_ACCESS + userId;
        String refreshTokenKey = RedisKeyConstants.USER_TOKEN_REFRESH + userId;

        // 获取当前活跃的 Token
        Object accessToken = redisOperator.get(accessTokenKey);
        Object refreshToken = redisOperator.get(refreshTokenKey);

        // 将活跃的 Token 加入黑名单
        if (accessToken != null) {
            blacklistToken(accessToken.toString());
        }
        if (refreshToken != null) {
            blacklistToken(refreshToken.toString());
        }

        // 清除 Redis 映射
        redisOperator.delete(accessTokenKey);
        redisOperator.delete(refreshTokenKey);
        redisOperator.delete(RedisKeyConstants.USER_HMAC_SECRET + userId);

        log.info("[Token] 已注销用户所有活跃Token, userId={}", userId);
    }

    /**
     * 严格版全量吊销（安全关键路径专用，如改密）
     * <p>
     * 与 {@link #revokeAllTokens(Long)} 的差异：Redis 不可用时<b>向上抛</b>
     * {@link com.myxhs.common.exception.RedisUnavailableException}，由调用方 fail-closed。
     * 原 invalidateUserCredentials 吞异常 → "改密成功但旧凭证仍有效"的静默安全缺口，已删除。
     * </p>
     */
    public void revokeAllTokensStrict(Long userId) {
        String accessTokenKey = RedisKeyConstants.USER_TOKEN_ACCESS + userId;
        String refreshTokenKey = RedisKeyConstants.USER_TOKEN_REFRESH + userId;

        Object accessToken = redisOperator.get(accessTokenKey);
        Object refreshToken = redisOperator.get(refreshTokenKey);

        if (accessToken != null) {
            blacklistTokenStrict(accessToken.toString());
        }
        if (refreshToken != null) {
            blacklistTokenStrict(refreshToken.toString());
        }

        redisOperator.delete(accessTokenKey);
        redisOperator.delete(refreshTokenKey);
        redisOperator.delete(RedisKeyConstants.USER_HMAC_SECRET + userId);

        log.info("[Token] 已严格吊销用户全部凭证, userId={}", userId);
    }

    /**
     * 解析并入黑名单：Redis 故障向上抛（fail-closed）；仅"已过期/损坏无法解析"跳过
     */
    private void blacklistTokenStrict(String token) {
        Claims claims;
        try {
            claims = JwtUtil.parseToken(token, jwtProperties.getSecret());
        } catch (Exception e) {
            log.warn("[Token] 凭证解析失败(可能已过期), 跳过黑名单: {}", e.getMessage());
            return;
        }
        blacklistByClaims(claims);
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
            log.info("[Token] 加入黑名单, jti={}, 剩余有效期={}秒", jti, remainingMs / 1000);
        }
    }

    /**
     * 将旧 access token 加入黑名单（单设备登录踢出）
     */
    private void blacklistOldToken(String token) {
        try {
            Claims claims = JwtUtil.parseToken(token, jwtProperties.getSecret());
            blacklistByClaims(claims);
        } catch (Exception e) {
            log.warn("[Token] 旧 token 解析失败(可能已过期), 跳过黑名单: {}", e.getMessage());
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
