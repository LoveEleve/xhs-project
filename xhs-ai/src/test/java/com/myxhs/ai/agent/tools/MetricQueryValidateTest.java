package com.myxhs.ai.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricQueryValidateTest {

    @Test
    void acceptsKnownMetrics() {
        assertThatCode(() -> MetricQueryBuilder.validatePromql(
                "sum by (application) (rate(http_server_requests_seconds_count[5m]))")).doesNotThrowAnyException();
        assertThatCode(() -> MetricQueryBuilder.validatePromql("up == 0")).doesNotThrowAnyException();
        assertThatCode(() -> MetricQueryBuilder.validatePromql("jvm_memory_used_bytes{area=\"heap\"}")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankUnknownOrTooLong() {
        assertThatThrownBy(() -> MetricQueryBuilder.validatePromql(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricQueryBuilder.validatePromql("foo_bar_metric > 1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已知指标名");
        assertThatThrownBy(() -> MetricQueryBuilder.validatePromql("up == 0".repeat(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过长");
    }
}
