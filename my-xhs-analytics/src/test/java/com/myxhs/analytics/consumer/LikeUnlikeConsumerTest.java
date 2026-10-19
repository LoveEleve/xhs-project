package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.entity.Like;
import com.myxhs.analytics.mapper.LikeMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.mq.VersionGateHelper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LikeUnlikeConsumer 单测（C-009 D3：完成态版本门流程）
 */
@ExtendWith(MockitoExtension.class)
class LikeUnlikeConsumerTest {

    @Mock private LikeMapper likeMapper;
    @Mock private IdGeneratorUtil idGeneratorUtil;
    @Mock private VersionGateHelper versionGate;

    private LikeUnlikeConsumer consumer;

    private static final String VERSION_KEY = "analytics:event:version:like:1001:1:500";

    @BeforeEach
    void setUp() {
        consumer = new LikeUnlikeConsumer(likeMapper, idGeneratorUtil, new ObjectMapper(), versionGate);
    }

    @Test
    @DisplayName("C-009：旧事件（version >= actionTime）→ 跳过（不落库不推进）")
    void staleEventSkipped() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(true);

        consumer.onMessage(message("LIKE", "{\"userId\":1001,\"bizType\":1,\"bizId\":500,\"action\":\"LIKE\",\"actionTime\":1000}"));

        verify(likeMapper, never()).insert(any(Like.class));
        verify(versionGate, never()).markDone(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("C-009：新事件 → 落库 + 完成后单调推进")
    void freshEventAppliedAndMarked() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(false);
        when(likeMapper.insert(any(Like.class))).thenReturn(1);

        consumer.onMessage(message("LIKE", "{\"userId\":1001,\"bizType\":1,\"bizId\":500,\"action\":\"LIKE\",\"actionTime\":1000}"));

        verify(likeMapper).insert(any(Like.class));
        verify(versionGate).markDone(VERSION_KEY, 1000L, 86400L);
    }

    @Test
    @DisplayName("C-009：处理失败 → 不推进（重投可重处理）+ 抛错触发重试")
    void failureNoMarkDone() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(false);
        when(likeMapper.insert(any(Like.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> consumer.onMessage(
                message("LIKE", "{\"userId\":1001,\"bizType\":1,\"bizId\":500,\"action\":\"LIKE\",\"actionTime\":1000}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LikeUnlike消费失败");

        verify(versionGate, never()).markDone(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("C-009：UNLIKE 路径 → 删除（幂等）+ 完成后推进")
    void unlikeAppliedAndMarked() {
        when(versionGate.isStale(VERSION_KEY, 1000L)).thenReturn(false);
        when(likeMapper.deleteByUserAndBiz(1001L, 1, 500L)).thenReturn(1);

        consumer.onMessage(message("UNLIKE", "{\"userId\":1001,\"bizType\":1,\"bizId\":500,\"action\":\"UNLIKE\",\"actionTime\":1000}"));

        verify(likeMapper).deleteByUserAndBiz(1001L, 1, 500L);
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
