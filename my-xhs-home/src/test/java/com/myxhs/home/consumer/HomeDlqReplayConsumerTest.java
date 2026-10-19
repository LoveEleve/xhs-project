package com.myxhs.home.consumer;

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
 * home BFF DLQ 重放消费者单测（C-014 F4）
 * <p>
 * 验证口径：重投目的地 / **tag 保留**（note-delete 按 selectorExpression 路由——丢 tag 消息无效）/
 * attempt≤3 / 失败不吞。
 * </p>
 */
class HomeDlqReplayConsumerTest {

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
        m.setTags(tags);
        m.setBody("{}".getBytes(StandardCharsets.UTF_8));
        if (replayCount != null) {
            m.getProperties().put(HomeDlqReplayConsumer.REPLAY_COUNT_PROPERTY, replayCount);
        }
        return m;
    }

    @Test
    @DisplayName("feed-push 组：重投 FEED_TOPIC，计数置 1")
    void feedPushReplay() {
        HomeDlqReplayConsumer.replay(msg("TAG", null), "FEED_TOPIC", rocketMQTemplate, businessMetrics);

        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("FEED_TOPIC:TAG"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(HomeDlqReplayConsumer.REPLAY_COUNT_PROPERTY)).isEqualTo("1");
        verify(businessMetrics).recordAction("home", "dlq_replay_ok");
    }

    @Test
    @DisplayName("note-delete 组：重投 SOCIAL_TOPIC:NOTE_DELETE（tag 保留必须）")
    void noteDeleteReplayKeepsTag() {
        HomeDlqReplayConsumer.replay(msg("NOTE_DELETE", "1"), "SOCIAL_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate).syncSend(eq("SOCIAL_TOPIC:NOTE_DELETE"), any(Message.class));
    }

    @Test
    @DisplayName("重放超限（attempt>=3）：不重投，仅告警指标")
    void overLimitStops() {
        HomeDlqReplayConsumer.replay(msg("TAG", "3"), "FEED_TOPIC", rocketMQTemplate, businessMetrics);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(businessMetrics).recordAction("home", "dlq_replay_over_limit");
    }

    @Test
    @DisplayName("重投失败：抛出不吞（本组重试/进本组 DLQ）")
    void failurePropagates() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"));

        assertThatThrownBy(() -> HomeDlqReplayConsumer.replay(
                msg("TAG", "1"), "FEED_TOPIC", rocketMQTemplate, businessMetrics))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DLQ 重投失败");
        verify(businessMetrics).recordAction("home", "dlq_replay_fail");
    }
}
