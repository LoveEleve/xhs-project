package com.myxhs.search.consumer;

import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索域 DLQ 重放消费者单测（C-011 R7）
 * <p>
 * 验证口径（对齐 C-007/8/9/10 家族）：
 * 保留 tag 重投 / 计数 +1 / 超限仅告警不重放 / 重投失败抛出不吞。
 * </p>
 */
class SearchDlqReplayConsumerTest {

    private RocketMQTemplate rocketMQTemplate;
    private BusinessMetrics businessMetrics;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        businessMetrics = mock(BusinessMetrics.class);
    }

    private static MessageExt msg(String tags, String replayCount) {
        MessageExt m = new MessageExt();
        m.setMsgId("MSG-1");
        m.setTopic("%DLQ%note-index-sync-consumer-group");
        m.setTags(tags);
        m.setBody("{\"noteId\":1}".getBytes(StandardCharsets.UTF_8));
        if (replayCount != null) {
            m.getProperties().put(SearchDlqReplayConsumer.REPLAY_COUNT_PROPERTY, replayCount);
        }
        return m;
    }

    @Test
    @DisplayName("首次重放：保留 tag 重投源 topic，计数置 1")
    void replayKeepsTagAndSetsCount() {
        SearchDlqReplayConsumer.replay(msg("INDEX", null), "NOTE_INDEX_TOPIC", rocketMQTemplate, businessMetrics);

        ArgumentCaptor<Message<String>> bodyCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("NOTE_INDEX_TOPIC:INDEX"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().getHeaders()
                .get(SearchDlqReplayConsumer.REPLAY_COUNT_PROPERTY)).isEqualTo("1");
        assertThat(bodyCaptor.getValue().getPayload()).isEqualTo("{\"noteId\":1}");
        verify(businessMetrics).recordAction("search", "dlq_replay_ok");
    }

    @Test
    @DisplayName("SOCIAL_TOPIC（tag 路由）：tag 必保留，否则投到无 tag 目的地会丢路由")
    void replaySocialTopicKeepsTag() {
        SearchDlqReplayConsumer.replay(msg("LIKE", "1"), "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);
        verify(rocketMQTemplate).syncSend(eq("SOCIAL_TOPIC:LIKE"), any(Message.class));
    }

    @Test
    @DisplayName("无 tag 消息：目的地=纯 topic")
    void replayWithoutTag() {
        SearchDlqReplayConsumer.replay(msg(null, null), "PRODUCT_INDEX_TOPIC", rocketMQTemplate, businessMetrics);
        verify(rocketMQTemplate).syncSend(eq("PRODUCT_INDEX_TOPIC"), any(Message.class));
    }

    @Test
    @DisplayName("重放超限（attempt>=3）：不重投，仅告警指标（防循环）")
    void replayOverLimitStops() {
        SearchDlqReplayConsumer.replay(msg("INDEX", "3"), "NOTE_INDEX_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordAction("search", "dlq_replay_over_limit");
    }

    @Test
    @DisplayName("重投失败：抛出让本组重试/进本组 DLQ（不静默丢消息）")
    void replayFailurePropagates() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"));

        assertThatThrownBy(() -> SearchDlqReplayConsumer.replay(
                msg("INDEX", "1"), "NOTE_INDEX_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DLQ 重投失败");
        verify(businessMetrics).recordAction("search", "dlq_replay_fail");
    }
}
