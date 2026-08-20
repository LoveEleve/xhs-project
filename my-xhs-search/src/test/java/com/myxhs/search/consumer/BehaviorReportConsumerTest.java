package com.myxhs.search.consumer;

import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BehaviorReportConsumerTest {

    @Test
    void shouldThrowWhenBehaviorWriteFailsSoRocketMqRetries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenThrow(new RuntimeException("database unavailable"));
        BehaviorReportConsumer consumer = new BehaviorReportConsumer(jdbcTemplate);

        MessageExt message = new MessageExt();
        message.setBody("{\"id\":1,\"userId\":2,\"noteId\":3,\"behaviorType\":3}".
                getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("推荐行为写库失败");
    }

    @Test
    void shouldRejectMalformedMessageSoItDoesNotAckAsSuccess() {
        BehaviorReportConsumer consumer = new BehaviorReportConsumer(mock(JdbcTemplate.class));
        MessageExt message = new MessageExt();
        message.setBody("{\"userId\":2,\"noteId\":3}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("推荐行为写库失败");
    }
}
