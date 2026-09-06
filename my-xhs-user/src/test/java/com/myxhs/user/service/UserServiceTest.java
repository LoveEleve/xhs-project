package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.request.UpdateUserRequest;
import com.myxhs.user.dto.response.UserInfoResponse;
import com.myxhs.user.entity.User;
import com.myxhs.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：UserMapper, TokenService, CaptchaService, RedisOperator, RedissonClient, CacheHelper, PasswordEncoder
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

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
    private SetOperations<String, String> setOperations;
    @Mock
    private TransactionTemplate transactionTemplate;

    private UserService userService;

    private static final Long USER_ID = 1001L;
    private static final Long TARGET_USER_ID = 2001L;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus 表元数据（LambdaUpdateWrapper 等需要）
        initMybatisPlusTableInfo(User.class);

        userService = new UserService(
                userMapper, tokenService, captchaService,
                redisOperator, redissonClient, stringRedisTemplate,
                cacheHelper, passwordEncoder, transactionTemplate
        );
    }

    // ==================== 更新用户信息 ====================

    @Test
    @DisplayName("更新用户信息 - 更新昵称和签名成功")
    void updateUserSuccess() {
        // Given
        User userBefore = buildUser();
        User userAfter = buildUser();
        userAfter.setNickname("新昵称");
        userAfter.setSignature("新签名");

        when(userMapper.selectById(USER_ID)).thenReturn(userBefore, userAfter);
        when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        doNothing().when(cacheHelper).delayDoubleDelete(anyString());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setNickname("新昵称");
        request.setSignature("新签名");

        // When
        UserInfoResponse response = userService.updateUserInfo(USER_ID, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getNickname()).isEqualTo("新昵称");
        assertThat(response.getSignature()).isEqualTo("新签名");
        verify(cacheHelper).delayDoubleDelete(anyString());
    }

    // ==================== 查询用户 ====================

    @Test
    @DisplayName("根据ID查询用户 - 返回正确的用户信息")
    void getUserByIdSuccess() {
        // Given
        User user = buildUser();
        when(cacheHelper.getWithCacheAside(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(user);

        // When
        UserInfoResponse response = userService.getUserInfo(USER_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getNickname()).isEqualTo("测试用户");
        assertThat(response.getSignature()).isEqualTo("测试签名");
    }

    @Test
    @DisplayName("根据ID查询用户 - 用户不存在抛出 BizException")
    void getUserByIdNotFound() {
        // Given
        when(cacheHelper.getWithCacheAside(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> userService.getUserInfo(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.USER_NOT_FOUND.getMessage());
    }

    // ==================== 屏蔽用户 ====================

    @Test
    @DisplayName("屏蔽用户 - 屏蔽成功，验证 Redis SADD 调用")
    void blockUserSuccess() {
        // Given
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(anyString(), any())).thenReturn(1L);

        // When
        assertThatCode(() -> userService.blockUser(USER_ID, TARGET_USER_ID))
                .doesNotThrowAnyException();

        // Then
        verify(setOperations).add(anyString(), any());
    }

    @Test
    @DisplayName("屏蔽用户 - 屏蔽自己抛出 BizException(\"不能屏蔽自己\")")
    void blockSelfThrows() {
        // Given & When & Then
        assertThatThrownBy(() -> userService.blockUser(USER_ID, USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能屏蔽自己");
    }

    // ==================== 辅助方法 ====================

    private User buildUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("testuser");
        user.setNickname("测试用户");
        user.setAvatar("https://example.com/avatar.jpg");
        user.setGender(1);
        user.setBirthday(LocalDate.of(2000, 1, 1));
        user.setPhone("13800138000");
        user.setEmail("test@example.com");
        user.setSignature("测试签名");
        user.setStatus(1);
        user.setPassword("encodedPassword");
        user.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        return user;
    }

    /**
     * 初始化 MyBatis-Plus 表元数据（LambdaUpdateWrapper 等需要）
     */
    private static void initMybatisPlusTableInfo(Class<?> entityClass) {
        try {
            MybatisConfiguration config = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(config, "");
            TableInfoHelper.initTableInfo(assistant, entityClass);
        } catch (Exception ignored) {
            // 已初始化则忽略
        }
    }
}
