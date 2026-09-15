package com.myxhs.ai.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricQueryBuilderTest {

    @Test
    void errorRateUsesStatusMatcherAndPercent() {
        String promql = MetricQueryBuilder.instant(MetricQueryBuilder.Metric.ERROR_RATE, null);
        assertThat(promql).contains("status=~\"5..\"").contains("by (application)").contains("* 100");
    }

    @Test
    void slowUriFiltersServiceAndGroupsByUri() {
        String promql = MetricQueryBuilder.instant(MetricQueryBuilder.Metric.SLOW_URI, "my-xhs-cart");
        assertThat(promql).contains("application=\"my-xhs-cart\"").contains("sum by (le, uri)");
        assertThat(promql).doesNotContain("sum by (le, application, uri)");
    }

    @Test
    void slowUriWithoutServiceGroupsByApplicationAndUri() {
        String promql = MetricQueryBuilder.instant(MetricQueryBuilder.Metric.SLOW_URI, null);
        assertThat(promql).contains("sum by (le, application, uri)");
    }

    @Test
    void invalidServiceRejected() {
        assertThatThrownBy(() -> MetricQueryBuilder.instant(MetricQueryBuilder.Metric.QPS, "bad\"name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trendRequiresServiceAndSupportsThreeMetrics() {
        assertThatThrownBy(() -> MetricQueryBuilder.trend(MetricQueryBuilder.Metric.QPS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MetricQueryBuilder.trend(MetricQueryBuilder.Metric.LATENCY_P95, "xhs-ai"))
                .contains("histogram_quantile(0.95").contains("application=\"xhs-ai\"");
        assertThat(MetricQueryBuilder.trend(MetricQueryBuilder.Metric.ERROR_RATE, "xhs-ai"))
                .contains("status=~\"5..\"");
    }

    @Test
    void minutesAndStepClamped() {
        assertThat(MetricQueryBuilder.clampMinutes(1)).isEqualTo(5);
        assertThat(MetricQueryBuilder.clampMinutes(99999)).isEqualTo(1440);
        assertThat(MetricQueryBuilder.trendStepSeconds(60)).isBetween(15, 300);
    }
}
