package com.myxhs.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.request.LikeRequest;
import com.myxhs.common.exception.BizException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LikeService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：StringRedisTemplate, RocketMQTemplate, DefaultRedisScript (likeAtomicScript, unlikeAtomicScript)
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LikeServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Mock(name = "likeAtomicScript")
    private DefaultRedisScript<Long> likeAtomicScript;

    @Mock(name = "unlikeAtomicScript")
    private DefaultRedisScript<Long> unlikeAtomicScript;

    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private com.myxhs.analytics.feign.ContentFeignClient contentFeignClient;

    private ObjectMapper objectMapper;
    private LikeService likeService;

    private static final Long USER_ID = 1001L;
    private static final Long BIZ_ID = 100L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        likeService = new LikeService(
                stringRedisTemplate, rocketMQTemplate, objectMapper,
                likeAtomicScript, unlikeAtomicScript, contentFeignClient);
        when(contentFeignClient.batchGetNoteDetail(anyList()))
                .thenReturn(com.myxhs.common.response.R.ok(
                        java.util.Map.of(String.valueOf(BIZ_ID), java.util.Map.of())));
    }

    // ==================== 点赞 ====================

    @Test
    @DisplayName("点赞 - 正常点赞成功，SADD返回1，MQ发送成功")
    void likeSuccess() {
        LikeRequest request = buildLikeRequest(1, BIZ_ID);

        // Lua脚本 SADD 成功返回 1（新增）
        when(stringRedisTemplate.execute(eq(likeAtomicScript), anyList(), anyString()))
                .thenReturn(1L);

        // MQ 同步发送成功
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(startsWith("SOCIAL_TOPIC:LIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L)))
                .thenReturn(sendResult);

        assertThatCode(() -> likeService.like(USER_ID, request))
                .doesNotThrowAnyException();

        // 验证 MQ 已发送
        verify(rocketMQTemplate).syncSend(startsWith("SOCIAL_TOPIC:LIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L));
    }

    @Test
    @DisplayName("点赞 - 重复点赞幂等，SADD返回0，不发送MQ")
    void likeDuplicatePrevention() {
        LikeRequest request = buildLikeRequest(1, BIZ_ID);

        // Lua脚本 SADD 返回 0（已存在）
        when(stringRedisTemplate.execute(eq(likeAtomicScript), anyList(), anyString()))
                .thenReturn(0L);

        assertThatCode(() -> likeService.like(USER_ID, request))
                .doesNotThrowAnyException();

        // 验证没有发送 MQ
        verify(rocketMQTemplate, never()).syncSend(anyString(),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), anyLong());
    }

    @Test
    @DisplayName("点赞 - MQ发送失败，不回滚Redis且接口不抛异常")
    void likeMqFailureRollback() {
        LikeRequest request = buildLikeRequest(1, BIZ_ID);

        // Lua脚本 SADD 成功返回 1
        when(stringRedisTemplate.execute(eq(likeAtomicScript), anyList(), anyString()))
                .thenReturn(1L);

        // MQ 同步发送失败（异常）
        when(rocketMQTemplate.syncSend(startsWith("SOCIAL_TOPIC:LIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L)))
                .thenThrow(new RuntimeException("MQ连接异常"));

        assertThatCode(() -> likeService.like(USER_ID, request))
                .doesNotThrowAnyException();

        verify(rocketMQTemplate).syncSend(startsWith("SOCIAL_TOPIC:LIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L));
        verify(stringRedisTemplate, never()).execute(eq(unlikeAtomicScript), anyList(), anyString());
    }

    // ==================== 取消点赞 ====================

    @Test
    @DisplayName("取消点赞 - 正常取消，SREM返回1，MQ发送成功")
    void unlikeSuccess() {
        LikeRequest request = buildLikeRequest(1, BIZ_ID);

        // Lua脚本 SREM 成功返回 1
        when(stringRedisTemplate.execute(eq(unlikeAtomicScript), anyList(), anyString()))
                .thenReturn(1L);

        // MQ 同步发送成功
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(startsWith("SOCIAL_TOPIC:UNLIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L)))
                .thenReturn(sendResult);

        assertThatCode(() -> likeService.unlike(USER_ID, request))
                .doesNotThrowAnyException();

        // 验证 MQ 已发送
        verify(rocketMQTemplate).syncSend(startsWith("SOCIAL_TOPIC:UNLIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L));
    }

    @Test
    @DisplayName("取消点赞 - MQ发送失败，回滚Redis并抛出BizException")
    void unlikeMqFailureRollback() {
        LikeRequest request = buildLikeRequest(1, BIZ_ID);

        // Lua脚本 SREM 成功返回 1
        when(stringRedisTemplate.execute(eq(unlikeAtomicScript), anyList(), anyString()))
                .thenReturn(1L);

        // MQ 发送失败（异常）
        when(rocketMQTemplate.syncSend(startsWith("SOCIAL_TOPIC:UNLIKE"),
                ArgumentMatchers.<org.springframework.messaging.Message>any(), eq(3000L)))
                .thenThrow(new RuntimeException("MQ连接异常"));

        assertThatThrownBy(() -> likeService.unlike(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("取消点赞失败，请重试");

        verify(stringRedisTemplate, org.mockito.Mockito.atLeastOnce()).opsForSet();
    }

    // ==================== 查询点赞状态 ====================

    @Test
    @DisplayName("查询点赞状态 - 已点赞返回true")
    void checkLikeStatusLiked() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(contains("note:100"), eq(String.valueOf(USER_ID))))
                .thenReturn(true);

        boolean result = likeService.isLiked(USER_ID, 1, BIZ_ID);

        assertThat(result).isTrue();
        verify(setOperations).isMember(contains("note:100"), eq(String.valueOf(USER_ID)));
    }

    @Test
    @DisplayName("查询点赞状态 - 未点赞返回false")
    void checkLikeStatusNotLiked() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(contains("note:200"), eq(String.valueOf(USER_ID))))
                .thenReturn(false);

        boolean result = likeService.isLiked(USER_ID, 1, 200L);

        assertThat(result).isFalse();
    }

    // ==================== 辅助方法 ====================

    private LikeRequest buildLikeRequest(int bizType, Long bizId) {
        LikeRequest request = new LikeRequest();
        request.setBizType(bizType);
        request.setBizId(bizId);
        return request;
    }
}
