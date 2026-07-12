package com.myxhs.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FavoriteService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：StringRedisTemplate, RocketMQTemplate, DefaultRedisScript (favoriteAtomicScript)
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FavoriteServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Mock(name = "favoriteAtomicScript")
    private DefaultRedisScript<Long> favoriteAtomicScript;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private ObjectMapper objectMapper;
    private FavoriteService favoriteService;

    private static final Long USER_ID = 1001L;
    private static final Long NOTE_ID = 200L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        favoriteService = new FavoriteService(
                stringRedisTemplate, rocketMQTemplate, objectMapper, favoriteAtomicScript);
    }

    // ==================== 收藏 ====================

    @Test
    @DisplayName("收藏笔记 - 正常收藏成功，验证 Redis Lua 脚本执行和 MQ 异步发送")
    void favoriteSuccess() {
        // Lua 脚本执行成功，返回 1（新增收藏）
        when(stringRedisTemplate.execute(eq(favoriteAtomicScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        doNothing().when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any());

        try (MockedStatic<MqTraceHelper> mockedMqTrace = mockStatic(MqTraceHelper.class)) {
            mockedMqTrace.when(() -> MqTraceHelper.wrapWithTraceId(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            assertThatCode(() -> favoriteService.favorite(USER_ID, NOTE_ID))
                    .doesNotThrowAnyException();

            verify(stringRedisTemplate).execute(eq(favoriteAtomicScript), anyList(), anyString(), anyString());
            verify(rocketMQTemplate).asyncSend(startsWith("SOCIAL_TOPIC:FAVORITE"), any(Message.class), any());
        }
    }

    // ==================== 取消收藏 ====================

    @Test
    @DisplayName("取消收藏 - 正常取消收藏成功，验证 Redis ZSet 移除和 MQ 异步发送")
    void unfavoriteSuccess() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.remove(anyString(), anyString())).thenReturn(1L);
        doNothing().when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any());

        try (MockedStatic<MqTraceHelper> mockedMqTrace = mockStatic(MqTraceHelper.class)) {
            mockedMqTrace.when(() -> MqTraceHelper.wrapWithTraceId(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            assertThatCode(() -> favoriteService.unfavorite(USER_ID, NOTE_ID))
                    .doesNotThrowAnyException();

            verify(zSetOperations).remove(startsWith("myxhs:favorite:"), eq(String.valueOf(NOTE_ID)));
            verify(rocketMQTemplate).asyncSend(startsWith("SOCIAL_TOPIC:UNFAVORITE"), any(Message.class), any());
        }
    }

    // ==================== 查询收藏状态 ====================

    @Test
    @DisplayName("查询收藏状态 - 已收藏返回 true")
    void checkFavoriteStatusTrue() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(startsWith("myxhs:favorite:"), eq(String.valueOf(NOTE_ID))))
                .thenReturn(1234567890.0);

        boolean result = favoriteService.isFavorited(USER_ID, NOTE_ID);

        assertThat(result).isTrue();
        verify(zSetOperations).score(startsWith("myxhs:favorite:"), eq(String.valueOf(NOTE_ID)));
    }

    @Test
    @DisplayName("查询收藏状态 - 未收藏返回 false")
    void checkFavoriteStatusFalse() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(startsWith("myxhs:favorite:"), eq(String.valueOf(NOTE_ID))))
                .thenReturn(null);

        boolean result = favoriteService.isFavorited(USER_ID, NOTE_ID);

        assertThat(result).isFalse();
    }
}
