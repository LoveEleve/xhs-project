package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.request.LoginRequest;
import com.myxhs.user.dto.response.CaptchaResponse;
import com.myxhs.user.dto.response.TokenResponse;
import com.myxhs.user.entity.User;
import com.myxhs.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试
 * <p>
 * 测试范围：UserService.login（登录流程）、CaptchaService（验证码生成与校验）。
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    // ==================== UserService 依赖 ====================

    @Mock
    private UserMapper userMapper;
    @Mock
    private TokenService tokenService;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private RedisOperator redisOperator;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private CacheHelper cacheHelper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TransactionTemplate transactionTemplate;

    private UserService userService;
    private CaptchaService realCaptchaService;

    private static final Long USER_ID = 1001L;
    private static final String USERNAME = "testuser";
    private static final String PASSWORD = "Password123";
    private static final String CAPTCHA_KEY = "captcha-key-123";
    private static final String CAPTCHA_CODE = "A3BX";

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userMapper, tokenService, captchaService,
                redisOperator, redissonClient, stringRedisTemplate,
                cacheHelper, passwordEncoder, transactionTemplate
        );
        when(redisOperator.getStringRedisTemplate()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        realCaptchaService = new CaptchaService(redisOperator, stringRedisTemplate);
    }

    // ==================== 登录测试 ====================

    @Test
    @DisplayName("登录成功 - 正确的用户名和密码返回 Token 对")
    void loginSuccess() {
        // Given
        LoginRequest request = buildLoginRequest();

        doNothing().when(captchaService).verifyCaptcha(CAPTCHA_KEY, CAPTCHA_CODE);
        when(redisOperator.get(anyString())).thenReturn(null);

        User user = buildUser();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches(PASSWORD, user.getPassword())).thenReturn(true);
        when(redisOperator.delete(anyString())).thenReturn(true);

        TokenResponse expectedToken = TokenResponse.builder()
                .accessToken("access-token-xxx")
                .refreshToken("refresh-token-xxx")
                .build();
        when(tokenService.generateTokenPair(USER_ID, null)).thenReturn(expectedToken);

        // When
        TokenResponse result = userService.login(request, "127.0.0.1");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("access-token-xxx");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token-xxx");
    }

    @Test
    @DisplayName("登录失败 - 密码错误抛出 BizException")
    void loginFailWrongPassword() {
        // Given
        LoginRequest request = buildLoginRequest();

        doNothing().when(captchaService).verifyCaptcha(CAPTCHA_KEY, CAPTCHA_CODE);
        when(redisOperator.get(anyString())).thenReturn(null);

        User user = buildUser();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches(PASSWORD, user.getPassword())).thenReturn(false);
        when(redisOperator.increment(anyString())).thenReturn(1L);

        // When & Then
        assertThatThrownBy(() -> userService.login(request, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    @DisplayName("登录失败 - 用户不存在抛出 BizException")
    void loginFailUserNotFound() {
        // Given
        LoginRequest request = buildLoginRequest();

        doNothing().when(captchaService).verifyCaptcha(CAPTCHA_KEY, CAPTCHA_CODE);
        when(redisOperator.get(anyString())).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(redisOperator.increment(anyString())).thenReturn(1L);

        // When & Then
        assertThatThrownBy(() -> userService.login(request, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    // ==================== 验证码测试 ====================

    @Test
    @DisplayName("验证码生成 - 返回非空的 captchaKey 和 captchaImage")
    void captchaGeneration() {
        // Given
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any());

        // When
        CaptchaResponse response = realCaptchaService.generateCaptcha();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getCaptchaKey()).isNotBlank();
        assertThat(response.getCaptchaImage()).isNotBlank();
        assertThat(response.getCaptchaImage()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("验证码校验 - 正确验证码校验通过，错误验证码抛出异常")
    void captchaValidation() {
        // Given: 正确验证码校验
        String validKey = "valid-key-123";
        String validCode = "B7KT";
        String redisKey = "myxhs:user:captcha:" + validKey;

        when(valueOperations.getAndDelete(redisKey)).thenReturn(validCode);

        // When & Then: 正确验证码应无异常
        realCaptchaService.verifyCaptcha(validKey, validCode);

        // Given: 错误验证码校验
        String wrongKey = "wrong-key-456";
        String wrongCode = "XXXX";
        String wrongRedisKey = "myxhs:user:captcha:" + wrongKey;

        when(valueOperations.getAndDelete(wrongRedisKey)).thenReturn("B7KT");

        // When & Then: 错误验证码应抛出异常
        assertThatThrownBy(() -> realCaptchaService.verifyCaptcha(wrongKey, wrongCode))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.CAPTCHA_ERROR.getMessage());
    }

    // ==================== 辅助方法 ====================

    private LoginRequest buildLoginRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(PASSWORD);
        request.setCaptchaKey(CAPTCHA_KEY);
        request.setCaptchaCode(CAPTCHA_CODE);
        return request;
    }

    private User buildUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setPassword("$2a$10$encrypted_password_hash_here");
        user.setStatus(1);
        return user;
    }
}
