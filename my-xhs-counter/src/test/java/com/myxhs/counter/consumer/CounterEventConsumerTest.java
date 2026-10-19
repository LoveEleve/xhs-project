package com.myxhs.counter.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.VersionGateHelper;
import com.myxhs.counter.service.CounterService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CounterEventConsumer 单测（C-009 D3：完成态版本门流程——点赞路径代表）
 */
@ExtendWith(MockitoExtension.class)
class CounterEventConsumerTest {

    @Mock private CounterService counterService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private VersionGateHelper versionGate;

    private CounterEventConsumer consumer;

    private static final String VERSION_KEY = "myxhs:counter:event:version:like:1001:1:500";

    @BeforeEach
    void setUp() {
        consumer = new CounterEventConsumer(counterService, new ObjectMapper(), stringRedisTemplate, versionGate);
    }

    @Test
    @DisplayName("C-009：旧点赞事件 → 版本门跳过（不更新计数不推进）")
    void staleLikeSkipped() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(true);

        consumer.onMessage(message("LIKE", "{\"bizType\":1,\"bizId\":500,\"userId\":1001,\"actionTime\":1000}"));

        verify(counterService, never()).setBasedLikeWithDedup(anyString(), anyInt(), anyLong(), anyLong(), anyBoolean());
        verify(versionGate, never()).markDone(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("C-009：新点赞事件 → Set-based 计数 + 完成后推进")
    void freshLikeAppliedAndMarked() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(false);
        when(counterService.setBasedLikeWithDedup(anyString(), eq(1), eq(500L), eq(1001L), eq(true)))
                .thenReturn(true);

        consumer.onMessage(message("LIKE", "{\"bizType\":1,\"bizId\":500,\"userId\":1001,\"actionTime\":1000}"));

        verify(counterService).setBasedLikeWithDedup(anyString(), eq(1), eq(500L), eq(1001L), eq(true));
        verify(versionGate).markDone(VERSION_KEY, 1000L, 86400L);
    }

    private MessageExt message(String tag, String body) {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-" + System.nanoTime());
        message.setTags(tag);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
