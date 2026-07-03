package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
    public void register(RegisterRequest request) {
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
    public TokenResponse login(LoginRequest request) {
        // 1. 校验验证码
        captchaService.verifyCaptcha(request.getCaptchaKey(), request.getCaptchaCode());

        String username = request.getUsername();

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
            incrementLoginFail(username);
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
            incrementLoginFail(username);
            throw new BizException(ResultCode.PASSWORD_ERROR, "用户名或密码错误");
        }

        // 6. 登录成功，清除失败计数
        clearLoginFail(username);

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
    public UserInfoResponse updateUserInfo(Long userId, UpdateUserRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
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
     * 登录失败计数 + 1
     */
    private void incrementLoginFail(String username) {
        String failKey = RedisKeyConstants.USER_LOGIN_FAIL + username;
        Long failCount = redisOperator.increment(failKey);

        log.info("[登录] 登录失败计数, username={}, 当前失败次数={}", username, failCount);

        if (failCount == 1) {
            // 首次失败，设置过期时间
            redisOperator.expire(failKey, 30, TimeUnit.MINUTES);
        }

        if (failCount >= MAX_LOGIN_FAIL) {
            // 锁定账号
            String lockKey = RedisKeyConstants.USER_LOGIN_LOCK + username;
            redisOperator.set(lockKey, "1", LOCK_MINUTES, TimeUnit.MINUTES);
            redisOperator.delete(failKey);
            log.warn("[登录] 账号被锁定, username={}, 锁定{}分钟", username, LOCK_MINUTES);
        }
    }

    /**
     * 清除登录失败计数
     */
    private void clearLoginFail(String username) {
        redisOperator.delete(RedisKeyConstants.USER_LOGIN_FAIL + username);
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

    /**
     * User → UserInfoResponse
     */
    private UserInfoResponse toUserInfoResponse(User user) {
        return UserInfoResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .birthday(user.getBirthday())
                .phone(UserInfoResponse.maskPhone(user.getPhone()))
                .email(UserInfoResponse.maskEmail(user.getEmail()))
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
