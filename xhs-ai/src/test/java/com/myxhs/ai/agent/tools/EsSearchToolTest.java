package com.myxhs.ai.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsSearchToolTest {

    @Test
    void validatesIndexWhitelist() {
        EsSearchTool.validateIndex("myxhs-logs-*");
        EsSearchTool.validateIndex("xhs-ai-knowledge");
        assertThatThrownBy(() -> EsSearchTool.validateIndex(".internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EsSearchTool.validateIndex("product_index"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void injectsSizeAndRejectsBadJson() throws Exception {
        String body = EsSearchTool.buildBody("{\"query\":{\"match_all\":{}}}", 5);
        assertThat(body).contains("\"size\":5");
        String withSize = EsSearchTool.buildBody("{\"query\":{\"match_all\":{}},\"size\":2}", 5);
        assertThat(withSize).contains("\"size\":2");
        assertThatThrownBy(() -> EsSearchTool.buildBody("not-json", 5))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> EsSearchTool.buildBody("[]", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labelPathSanitizesName() {
        assertThat(MetricQueryBuilder.labelPath(null, null)).isEqualTo("/api/v1/labels");
        assertThat(MetricQueryBuilder.labelPath("application", "up==0"))
                .contains("/api/v1/label/application/values").contains("match%5B%5D=");
        assertThatThrownBy(() -> MetricQueryBuilder.labelPath("bad name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
