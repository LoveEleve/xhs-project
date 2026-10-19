package com.myxhs.content.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.mapper.LocalMessageMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FeedMessageRetryJob 单测（C-010：补发泛化——按原始 body 重投）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedMessageRetryJobTest {

    @Mock private LocalMessageMapper localMessageMapper;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private FeedMessageRetryJob job;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        job = new FeedMessageRetryJob(localMessageMapper, businessMetrics, rocketMQTemplate,
                objectMapper, stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);
    }

    private LocalMessage row(Long id, String topic, String body) {
        LocalMessage msg = new LocalMessage();
        msg.setId(id);
        msg.setTopic(topic);
        msg.setBody(body);
        msg.setStatus(0);
        msg.setRetryCount(0);
        return msg;
    }

    @Test
    @DisplayName("C-010：非 FEED 行 → 按原始 body 重投（计数事件泛化）")
    void retryNonFeedRowSendsRawBody() {
        when(localMessageMapper.selectPending(any(LocalDateTime.class), eq(3), eq(500)))
                .thenReturn(List.of(row(1L, "SOCIAL_TOPIC:UNCOMMENT", "{\"noteId\":100,\"count\":2}")));

        job.retryFailedMessages();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).asyncSend(eq("SOCIAL_TOPIC:UNCOMMENT"), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue().toString()).contains("\"count\":2");
    }

    @Test
    @DisplayName("C-010：FEED legacy 行（localMsgId null）→ 补丁后重投")
    void retryFeedLegacyRowPatchesLocalMsgId() {
        when(localMessageMapper.selectPending(any(LocalDateTime.class), eq(3), eq(500)))
                .thenReturn(List.of(row(2L, "FEED_TOPIC", "{\"noteId\":100,\"authorId\":1}")));

        job.retryFailedMessages();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).asyncSend(eq("FEED_TOPIC"), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue().toString()).contains("\"localMsgId\":2");
    }

    @Test
    @DisplayName("C-010 N3：补发异常且达上限 → 死信指标（send_retry_exceeded）")
    void retryDeadLetterRecordsMetric() {
        LocalMessage msg = row(3L, "FEED_TOPIC", "{\"noteId\":1}");
        msg.setRetryCount(2); // +1 = 3 = MAX_RETRY → 死信
        when(localMessageMapper.selectPending(any(LocalDateTime.class), eq(3), eq(500)))
                .thenReturn(List.of(msg));
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).asyncSend(anyString(), any(Object.class), any());

        job.retryFailedMessages();

        verify(localMessageMapper).incrementRetry(3L, 3);
        verify(businessMetrics).recordFeedPushTerminal("send_retry_exceeded");
    }
}
