package com.myxhs.home.consumer;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Feed 推送消费者单测（C-014）
 * <p>
 * 验证口径：已删标记拦截 + 四路径 markPushCompleted（含 F7 保护路径）/ 大V拉模式 / 推模式进度 /
 * G6 zCard 单调用 / F7 阈值 `>=`。
 * </p>
 */
@SuppressWarnings("unchecked")
class FeedPushConsumerTest {

    private static final String PROGRESS_KEY = "myxhs:feed:push:progress:5001";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zsetOps;
    private BusinessMetrics businessMetrics;
    private FeedPushConsumer consumer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        businessMetrics = mock(BusinessMetrics.class);
        consumer = new FeedPushConsumer(redisTemplate, businessMetrics);
        ReflectionTestUtils.setField(consumer, "bigVThreshold", 100000L);
        ReflectionTestUtils.setField(consumer, "inboxMaxDays", 7);
        ReflectionTestUtils.setField(consumer, "inboxMaxSize", 500);
    }

    private static MessageExt msg(long noteId, long authorId) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setBody(("{\"noteId\":" + noteId + ",\"authorId\":" + authorId
                + ",\"publishTime\":1715510539000,\"localMsgId\":5001}").getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    @DisplayName("已删标记命中 → 跳过推送 + markPushCompleted（补偿 Job 不重投）")
    void deletedNoteSkippedAndCompleted() {
        when(redisTemplate.hasKey(RedisKeyConstants.NOTE_DELETED_MARK + 101L)).thenReturn(true);

        consumer.onMessage(msg(101L, 7L));

        verify(hashOps).put(PROGRESS_KEY, "status", "completed");
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("大V（缓存命中）→ 写发件箱 + markPushCompleted")
    void bigVWritesOutbox() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOps.get("myxhs:user:bigv:7")).thenReturn("1");

        consumer.onMessage(msg(101L, 7L));

        verify(zsetOps).add(RedisKeyConstants.FEED_OUTBOX + 7L, "101", 1715510539000.0);
        verify(hashOps).put(PROGRESS_KEY, "status", "completed");
    }

    @Test
    @DisplayName("推模式：粉丝批推（inbox + following 召回源）+ 进度 + 完成标记")
    void pushToFollowersWithProgress() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOps.get("myxhs:user:bigv:7")).thenReturn("0");
        when(zsetOps.zCard(RedisKeyConstants.FOLLOW_FANS + 7L)).thenReturn(2L);
        when(zsetOps.range(RedisKeyConstants.FOLLOW_FANS + 7L, 0, 499)).thenReturn(Set.of("f1", "f2"));
        when(hashOps.get(PROGRESS_KEY, "cursor")).thenReturn(null);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(java.util.Collections.emptyList());

        consumer.onMessage(msg(101L, 7L));

        verify(redisTemplate, atLeastOnce()).executePipelined(any(RedisCallback.class));
        verify(hashOps).put(PROGRESS_KEY, "total", "2");
        verify(hashOps).put(PROGRESS_KEY, "cursor", "500");
        verify(hashOps).put(PROGRESS_KEY, "status", "completed");
        // C-014 G6：zCard 单调用
        verify(zsetOps, times(1)).zCard(anyString());
    }

    @Test
    @DisplayName("C-014 F7：粉丝数恰在阈值（缓存陈旧 0）→ `>=` 保护生效 + outbox + markPushCompleted")
    void protectionBoundaryAtThreshold() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOps.get("myxhs:user:bigv:7")).thenReturn("0"); // 陈旧负缓存
        when(zsetOps.zCard(RedisKeyConstants.FOLLOW_FANS + 7L)).thenReturn(100000L);

        consumer.onMessage(msg(101L, 7L));

        verify(zsetOps).add(RedisKeyConstants.FEED_OUTBOX + 7L, "101", 1715510539000.0);
        verify(hashOps).put(PROGRESS_KEY, "status", "completed"); // F7：原漏标
        verify(zsetOps, never()).range(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("字段缺失 → 告警跳过；处理异常 → 抛出触发 MQ 重试")
    void malformedAndFailure() {
        MessageExt bad = new MessageExt();
        bad.setMsgId("M2");
        bad.setBody("{\"noteId\":101}".getBytes(StandardCharsets.UTF_8));
        consumer.onMessage(bad); // 不抛

        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis down"));
        assertThatThrownBy(() -> consumer.onMessage(msg(101L, 7L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Feed推送失败");
    }
}
