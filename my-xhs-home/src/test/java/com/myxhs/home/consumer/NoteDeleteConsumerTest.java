package com.myxhs.home.consumer;

import com.myxhs.common.constants.RedisKeyConstants;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 笔记删除消费者单测（C-014）
 * <p>
 * 验证口径：G6 已删标记先行（清理前设置，拦截窗口最小化）+ outbox 清理 + following SCAN 清理 + 常量。
 * </p>
 */
@SuppressWarnings("unchecked")
class NoteDeleteConsumerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ZSetOperations<String, String> zsetOps;
    private NoteDeleteConsumer consumer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        consumer = new NoteDeleteConsumer(redisTemplate);
    }

    private static MessageExt msg(long noteId, long authorId) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setBody(("{\"noteId\":" + noteId + ",\"userId\":" + authorId + "}").getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    @DisplayName("C-014 G6：已删标记**先行**（清理前）——InOrder 锁定")
    void markerSetBeforeCleanup() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        consumer.onMessage(msg(101L, 7L));

        InOrder inOrder = inOrder(redisTemplate, zsetOps, valueOps);
        // 标记先行（原实现标记在清理后——清理与标记之间晚到的 FEED_TOPIC 重投会写回 outbox）
        inOrder.verify(valueOps).set(eq(RedisKeyConstants.NOTE_DELETED_MARK + 101L), eq("1"), eq(Duration.ofDays(7)));
        // 随后清理 outbox
        inOrder.verify(zsetOps).remove(RedisKeyConstants.FEED_OUTBOX + 7L, "101");
        // following:latest 全量 SCAN 清理
        inOrder.verify(redisTemplate).scan(any(ScanOptions.class));
    }

    @Test
    @DisplayName("SCAN 清理 following:latest：命中 noteId 的 key 逐个 ZREM")
    void scanCleanup() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("myxhs:recommend:following:latest:1", "myxhs:recommend:following:latest:2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(zsetOps.remove(anyString(), eq("101"))).thenReturn(1L);

        consumer.onMessage(msg(101L, 7L));

        verify(zsetOps).remove("myxhs:recommend:following:latest:1", "101");
        verify(zsetOps).remove("myxhs:recommend:following:latest:2", "101");
    }

    @Test
    @DisplayName("消息体解析失败 → 告警跳过；处理异常 → 抛出触发 MQ 重试")
    void malformedAndFailure() {
        MessageExt bad = new MessageExt();
        bad.setMsgId("M2");
        bad.setBody("{}".getBytes(StandardCharsets.UTF_8));
        consumer.onMessage(bad); // 不抛

        doThrow(new RuntimeException("Redis down")).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        assertThatThrownBy(() -> consumer.onMessage(msg(101L, 7L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("NoteDelete消费失败");
    }
}
