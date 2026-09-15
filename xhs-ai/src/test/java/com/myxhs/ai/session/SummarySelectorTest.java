package com.myxhs.ai.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SummarySelectorTest {

    private List<Map<String, Object>> messages(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (long) i);
            m.put("content", "m" + i);
            list.add(m);
        }
        return list;
    }

    @Test
    void picksOlderUnsummarizedBlockOnly() {
        List<Map<String, Object>> picked = SummarySelector.pickBlock(messages(50), 10, 20, 5);
        assertThat(picked).hasSize(20);
        assertThat(picked.get(0).get("id")).isEqualTo(11L);
        assertThat(picked.get(19).get("id")).isEqualTo(30L);
    }

    @Test
    void returnsEmptyWhenBatchTooSmall() {
        assertThat(SummarySelector.pickBlock(messages(25), 0, 20, 10)).isEmpty();
        assertThat(SummarySelector.pickBlock(messages(50), 40, 5, 6)).isEmpty();
        assertThat(SummarySelector.pickBlock(messages(50), 40, 5, 5)).hasSize(5);
    }
}
