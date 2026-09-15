package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogQueryBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void topServicesBodyUsesKeywordFieldAndClamps() throws Exception {
        JsonNode root = mapper.readTree(LogQueryBuilder.topServicesBody("ERROR", 99999, 999));
        assertThat(root.path("size").asInt()).isZero();
        assertThat(root.path("aggs").path("top_services").path("terms").path("field").asText())
                .isEqualTo("APP_NAME.keyword");
        assertThat(root.path("aggs").path("top_services").path("terms").path("size").asInt())
                .isEqualTo(LogQueryBuilder.MAX_TOP_N);
        assertThat(root.path("query").path("bool").path("filter").get(0).path("range")
                .path("@timestamp").path("gte").asText()).isEqualTo("now-1440m");
        assertThat(root.path("query").path("bool").path("filter").get(1).path("term")
                .path("level.keyword").asText()).isEqualTo("ERROR");
    }

    @Test
    void searchBodyBuildsFiltersAndMustClause() throws Exception {
        JsonNode root = mapper.readTree(LogQueryBuilder.searchBody("my-xhs-cart", "ERROR", "timeout", 30, 5));
        assertThat(root.path("size").asInt()).isEqualTo(5);
        JsonNode filters = root.path("query").path("bool").path("filter");
        assertThat(filters).hasSize(3);
        assertThat(filters.get(1).path("term").path("level.keyword").asText()).isEqualTo("ERROR");
        assertThat(filters.get(2).path("term").path("APP_NAME.keyword").asText()).isEqualTo("my-xhs-cart");
        assertThat(root.path("query").path("bool").path("must").path("match").path("message").asText())
                .isEqualTo("timeout");
        assertThat(root.path("sort").get(0).path("@timestamp").path("order").asText()).isEqualTo("desc");
    }

    @Test
    void defaultsHaveNoOptionalFilters() throws Exception {
        JsonNode root = mapper.readTree(LogQueryBuilder.searchBody(null, "ERROR", null, 60, 20));
        assertThat(root.path("query").path("bool").path("filter")).hasSize(2);
        assertThat(root.path("query").path("bool").has("must")).isFalse();
    }
}
