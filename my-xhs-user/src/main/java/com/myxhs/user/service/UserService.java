package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.exception.RedisUnavailableException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.request.ChangePasswordRequest;
import com.myxhs.user.dto.request.LoginRequest;
import com.myxhs.user.dto.request.RegisterRequest;
import com.myxhs.user.dto.request.UpdateUserRequest;
import com.myxhs.user.dto.response.TokenResponse;
import com.myxhs.user.dto.response.UserInfoResponse;
import com.myxhs.user.dto.response.UserPublicInfoResponse;
import com.myxhs.user.entity.User;
import com.myxhs.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务
 * <p>
 * 核心业务逻辑：注册、登录、个人信息管理。
 * 安全措施：BCrypt 加密、分布式锁防并发注册、登录失败计数+账号锁定。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final CaptchaService captchaService;
    private final RedisOperator redisOperator;
    private final RedissonClient redissonClient;
    private final CacheHelper cacheHelper;

    private final PasswordEncoder passwordEncoder;

    /** 登录失败最大次数 */
    private static final int MAX_LOGIN_FAIL = 5;
    /** 单 IP 登录失败最大次数（P2-9：达到即锁 IP，防单源账号 DoS） */
    private static final int MAX_IP_FAIL = 20;
    /** 账号锁定时间（分钟） */
    private static final int LOCK_MINUTES = 15;

    // ==================== 注册 ====================

    /**
     * 用户注册
     * <p>
     * 1. 校验验证码
     * 2. 分布式锁防并发注册
     * 3. 检查用户名/手机号唯一性
     * 4. BCrypt 加密密码
     * 5. 插入数据库
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        try {
            doRegister(request);
        } catch (RedisUnavailableException e) {
            log.error("[注册] Redis 不可用, username={}", request.getUsername(), e);
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
        }
    }

    private void doRegister(RegisterRequest request) {
        // 1. 校验验证码
        captchaService.verifyCaptcha(request.getCaptchaKey(), request.getCaptchaCode());

        // 2. 分布式锁（以用户名为粒度）
        String lockKey = RedisKeyConstants.USER_REGISTER_LOCK + request.getUsername();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }

            // 3. 检查用户名是否已存在
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
            );
            if (count > 0) {
                throw new BizException(ResultCode.USERNAME_EXISTS);
            }

            // 4. 检查手机号是否已存在（如果提供了手机号）
            if (request.getPhone() != null && !request.getPhone().isEmpty()) {
                Long phoneCount = userMapper.selectCount(
                        new LambdaQueryWrapper<User>().eq(User::getPhone, request.getPhone())
                );
                if (phoneCount > 0) {
                    throw new BizException(ResultCode.PHONE_EXISTS);
                }
            }

            // 5. 创建用户
            User user = new User();
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setNickname(request.getUsername()); // 默认昵称 = 用户名
            user.setPhone(request.getPhone());
            user.setGender(0);
            user.setStatus(1);

            userMapper.insert(user);
            log.info("[注册] 用户注册成功, userId={}, username={}", user.getId(), user.getUsername());

        } catch (DuplicateKeyException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("username")) {
                throw new BizException(ResultCode.USERNAME_EXISTS);
            }
            if (msg != null && msg.contains("phone")) {
                throw new BizException(ResultCode.PHONE_EXISTS);
            }
            throw new BizException(ResultCode.USERNAME_EXISTS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 登录 ====================

    /**
     * 用户登录
     * <p>
     * 1. 校验验证码
     * 2. 检查账号是否被锁定
     * 3. 查询用户 + 校验密码
     * 4. 登录失败计数（5 次锁定 15 分钟）
     * 5. 登录成功 → 清除失败计数 → 生成 Token 对
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse login(LoginRequest request, String clientIp) {
        // Redis 不可用时返回明确错误，而非 500
        try {
            return doLogin(request, clientIp);
        } catch (RedisUnavailableException e) {
            log.error("[登录] Redis 不可用, username={}", request.getUsername(), e);
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
        }
    }

    private TokenResponse doLogin(LoginRequest request, String clientIp) {
        // 1. 校验验证码
        captchaService.verifyCaptcha(request.getCaptchaKey(), request.getCaptchaCode());

        String username = request.getUsername();

        // 1.5 P2-9：IP 维度锁定检查（单源攻击先锁 IP，不锁账号，防账号 DoS）
        if (clientIp != null && !clientIp.isEmpty()) {
            String ipLockKey = RedisKeyConstants.USER_LOGIN_LOCK_IP + clientIp;
            if (redisOperator.get(ipLockKey) != null) {
                log.warn("[登录] IP 已被锁定(疑似攻击), ip={}, username={}", clientIp, username);
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "尝试过于频繁，请稍后再试");
            }
        }

        // 2. 检查账号是否被锁定
        String lockKey = RedisKeyConstants.USER_LOGIN_LOCK + username;
        if (redisOperator.get(lockKey) != null) {
            log.info("[登录] 账号已被锁定, username={}", username);
            throw new BizException(ResultCode.ACCOUNT_LOCKED);
        }

        // 3. 查询用户（@TableLogic 会自动追加 deleted = 0 条件）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            log.info("[登录] 用户不存在, username={}", username);
            throw new BizException(ResultCode.PASSWORD_ERROR, "用户名或密码错误");
        }

        // 4. 检查账号状态
        if (user.getStatus() == 0) {
            log.info("[登录] 账号已禁用, userId={}, username={}", user.getId(), username);
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 5. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.info("[登录] 密码错误, userId={}, username={}", user.getId(), username);
            incrementLoginFail(username, clientIp);
            throw new BizException(ResultCode.PASSWORD_ERROR, "用户名或密码错误");
        }

        // 6. 登录成功，清除失败计数
        clearLoginFail(username, clientIp);

        // 7. 生成 Token 对
        TokenResponse tokenResponse = tokenService.generateTokenPair(user.getId());
        log.info("[登录] 用户登录成功, userId={}, username={}", user.getId(), username);
        return tokenResponse;
    }

    // ==================== Token 刷新 ====================

    /**
     * 刷新 Token
     */
    public TokenResponse refreshToken(String refreshToken) {
        return tokenService.refreshToken(refreshToken);
    }

    // ==================== 注销 ====================

    /**
     * 退出登录
     */
    public void logout(String accessToken, String refreshToken) {
        tokenService.logout(accessToken, refreshToken);
    }

    // ==================== 用户信息 ====================

    /**
     * 获取用户公开信息（精简版，不含敏感字段）
     * <p>
     * 用于公开接口（如查看他人主页），只返回昵称、头像、性别、签名等非敏感字段。
     * </p>
     */
    public UserPublicInfoResponse getUserPublicInfo(Long userId) {
        User user = getUserFromCache(userId);
        return toUserPublicInfoResponse(user);
    }

    /**
     * 获取用户信息（带缓存）
     */
    public UserInfoResponse getUserInfo(Long userId) {
        User user = getUserFromCache(userId);
        log.info("[用户] 获取用户信息成功, userId={}, username={}", userId, user.getUsername());
        return toUserInfoResponse(user);
    }

    /**
     * 更新用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public UserInfoResponse updateUserInfo(Long userId, UpdateUserRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        // 检查账号状态：禁用用户不可修改个人信息
        if (user.getStatus() == 0) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 至少一个字段有值，避免空 wrapper → SQL 语法错误 500
        if (request.hasNoFields()) {
            throw new BizException(ResultCode.PARAM_INVALID, "至少需要修改一个字段");
        }

        // 检查手机号唯一性
        if (request.getPhone() != null && !request.getPhone().equals(user.getPhone())) {
            Long phoneCount = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getPhone, request.getPhone())
                            .ne(User::getId, userId)
            );
            if (phoneCount > 0) {
                throw new BizException(ResultCode.PHONE_EXISTS);
            }
        }

        // 更新字段（只更新非 null 字段）
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, userId);

        if (request.getNickname() != null) updateWrapper.set(User::getNickname, request.getNickname());
        if (request.getAvatar() != null) updateWrapper.set(User::getAvatar, request.getAvatar());
        if (request.getGender() != null) updateWrapper.set(User::getGender, request.getGender());
        if (request.getBirthday() != null) updateWrapper.set(User::getBirthday, request.getBirthday());
        if (request.getPhone() != null) updateWrapper.set(User::getPhone, request.getPhone());
        if (request.getEmail() != null) updateWrapper.set(User::getEmail, request.getEmail());
        if (request.getSignature() != null) updateWrapper.set(User::getSignature, request.getSignature());

        // 4. 先更新 DB
        userMapper.update(null, updateWrapper);

        // 5. 再延迟双删（正确顺序：先更新DB → 再删缓存）
        String cacheKey = RedisKeyConstants.USER_INFO + userId;
        cacheHelper.delayDoubleDelete(cacheKey);

        log.info("[用户] 更新用户信息, userId={}", userId);
        return getUserInfoDirect(userId);
    }

    /**
     * 修改密码
     * <p>
     * 【修复M13】密码修改成功后，注销该用户当前所有活跃 Token，
     * 防止旧 Token（access 30min + refresh 7d）继续有效。
     * </p>
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        // 检查账号状态：禁用用户不可修改密码
        if (user.getStatus() == 0) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 校验旧密码
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            log.info("[用户] 修改密码失败(旧密码错误), userId={}", userId);
            throw new BizException(ResultCode.PASSWORD_ERROR, "旧密码错误");
        }

        // 更新密码
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(updateUser);

        // 注销当前用户所有活跃 Token（加入黑名单 + 清除 Redis 映射）
        tokenService.revokeAllTokens(userId);

        log.info("[用户] 修改密码成功(已注销旧Token), userId={}", userId);
    }

    // ==================== 私有方法 ====================

    /**
     * 登录失败计数 + 1（P2-9：增加 IP 维度，防单源账号 DoS）
     * <p>
     * 策略：
     * - 按 IP 计数：同一 IP 失败达 {@link #MAX_IP_FAIL} 次 → 锁定该 IP（单源攻击先锁 IP，不锁账号）。
     * - 按账号计数：仅当失败来源 IP 数 &gt;= 2（疑似分布式暴力破解）时才锁定账号；
     *   单源攻击不会锁定账号（攻击者 IP 已被锁），避免账号被恶意锁死。
     * </p>
     */
    private void incrementLoginFail(String username, String clientIp) {
        // 1. IP 维度计数（无 IP 时跳过 IP 维度，退化为原逻辑）
        if (clientIp != null && !clientIp.isEmpty()) {
            String ipFailKey = RedisKeyConstants.USER_LOGIN_FAIL_IP + clientIp;
            Long ipFailCount = redisOperator.increment(ipFailKey);
            if (ipFailCount != null && ipFailCount == 1) {
                redisOperator.expire(ipFailKey, 30, TimeUnit.MINUTES);
            }
            if (ipFailCount != null && ipFailCount >= MAX_IP_FAIL) {
                String ipLockKey = RedisKeyConstants.USER_LOGIN_LOCK_IP + clientIp;
                redisOperator.set(ipLockKey, "1", LOCK_MINUTES, TimeUnit.MINUTES);
                redisOperator.delete(ipFailKey);
                log.warn("[登录] IP 已被锁定, ip={}, 失败{}次, 锁定{}分钟", clientIp, ipFailCount, LOCK_MINUTES);
            }
        }

        // 2. 账号维度计数 + 记录失败来源 IP
        String failKey = RedisKeyConstants.USER_LOGIN_FAIL + username;
        Long failCount = redisOperator.increment(failKey);

        log.info("[登录] 登录失败计数, username={}, 当前失败次数={}, ip={}", username, failCount, clientIp);

        if (failCount != null && failCount == 1) {
            // 首次失败，设置过期时间
            redisOperator.expire(failKey, 30, TimeUnit.MINUTES);
        }

        // 3. 记录失败来源 IP（判定是否为多 IP 暴力破解）
        long distinctIps = 0;
        if (clientIp != null && !clientIp.isEmpty()) {
            String ipsKey = RedisKeyConstants.USER_LOGIN_FAIL_IPS + username;
            redisOperator.sAdd(ipsKey, clientIp);
            redisOperator.expire(ipsKey, 30, TimeUnit.MINUTES);
            Set<Object> ips = redisOperator.sMembers(ipsKey);
            distinctIps = ips == null ? 0 : ips.size();
        }

        // 4. 仅当失败次数达标 且 来自多个不同 IP（>=2）时才锁定账号
        if (failCount != null && failCount >= MAX_LOGIN_FAIL && distinctIps >= 2) {
            // 锁定账号
            String lockKey = RedisKeyConstants.USER_LOGIN_LOCK + username;
            redisOperator.set(lockKey, "1", LOCK_MINUTES, TimeUnit.MINUTES);
            redisOperator.delete(failKey);
            redisOperator.delete(RedisKeyConstants.USER_LOGIN_FAIL_IPS + username);
            log.warn("[登录] 账号被锁定(多IP暴力破解), username={}, 失败{}次, 来自{}个IP, 锁定{}分钟",
                    username, failCount, distinctIps, LOCK_MINUTES);
        }
    }

    /**
     * 清除登录失败计数
     */
    private void clearLoginFail(String username, String clientIp) {
        redisOperator.delete(RedisKeyConstants.USER_LOGIN_FAIL + username);
        redisOperator.delete(RedisKeyConstants.USER_LOGIN_FAIL_IPS + username);
        // 登录成功不清理 IP 计数（IP 计数用于拦截攻击来源，独立于账号）
    }

    /**
     * 从缓存获取用户信息（统一入口，消除重复代码）
     * <p>
     * Cache Aside 模式：先查缓存 → 未命中查 DB → 回填缓存。
     * 缓存查询排除 password 字段，避免敏感信息存入 Redis。
     * getUserInfo 和 getUserPublicInfo 共享同一份缓存数据，各自提取不同字段返回。
     * </p>
     */
    private User getUserFromCache(Long userId) {
        User user = cacheHelper.getWithCacheAside(
                RedisKeyConstants.USER_INFO + userId,
                () -> userMapper.selectOne(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getId, userId)
                                .select(User::getId, User::getUsername, User::getNickname,
                                        User::getAvatar, User::getGender, User::getBirthday,
                                        User::getPhone, User::getEmail, User::getSignature,
                                        User::getStatus, User::getCreatedAt)
                ),
                30, TimeUnit.MINUTES
        );

        if (user == null) {
            log.info("[用户] 用户不存在, userId={}", userId);
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 直接查 DB 获取用户信息（不走缓存，用于更新后返回最新数据）
     */
    private UserInfoResponse getUserInfoDirect(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        return toUserInfoResponse(user);
    }

    // ==================== 屏蔽管理 ====================

    /**
     * 屏蔽用户
     */
    public void blockUser(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.BAD_REQUEST, "不能屏蔽自己");
        }
        String blockKey = RedisKeyConstants.USER_BLOCK_LIST + userId;
        redisOperator.sAdd(blockKey, targetUserId.toString());
        redisOperator.expire(blockKey, 365, TimeUnit.DAYS);
        log.info("[屏蔽] 用户屏蔽成功: userId={}, targetUserId={}", userId, targetUserId);
    }

    /**
     * 取消屏蔽
     */
    public void unblockUser(Long userId, Long targetUserId) {
        String blockKey = RedisKeyConstants.USER_BLOCK_LIST + userId;
        redisOperator.sRemove(blockKey, targetUserId.toString());
        log.info("[屏蔽] 用户取消屏蔽: userId={}, targetUserId={}", userId, targetUserId);
    }

    /**
     * 获取屏蔽用户列表
     */
    public Set<Object> getBlockList(Long userId) {
        String blockKey = RedisKeyConstants.USER_BLOCK_LIST + userId;
        return redisOperator.sMembers(blockKey);
    }

    /**
     * User → UserInfoResponse
     */
    private UserInfoResponse toUserInfoResponse(User user) {
        // /me 是用户查看自己的信息，phone/email 返回完整值（不脱敏）
        // 脱敏只在公开接口 toUserPublicInfoResponse 中体现（完全剔除 phone/email）
        return UserInfoResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .birthday(user.getBirthday())
                .phone(user.getPhone())
                .email(user.getEmail())
                .signature(user.getSignature())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * User → UserPublicInfoResponse（只含非敏感字段）
     */
    private UserPublicInfoResponse toUserPublicInfoResponse(User user) {
        return UserPublicInfoResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .signature(user.getSignature())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
