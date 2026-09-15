package com.myxhs.ai.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryRebuilderTest {

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    void noHistoryReturnsOriginal() {
        assertThat(SessionHistoryRebuilder.build(List.of(), "新问题", 6, 100)).isEqualTo("新问题");
        assertThat(SessionHistoryRebuilder.build(null, "新问题", 6, 100)).isEqualTo("新问题");
    }

    @Test
    void keepsRecentMessagesAndTruncates() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("user", "旧问题"));
        history.add(msg("assistant", "旧回答"));
        history.add(msg("user", "最近问题"));
        history.add(msg("assistant", "最近回答"));
        String built = SessionHistoryRebuilder.build(history, "继续", 2, 10);
        assertThat(built).contains("历史对话恢复").contains("用户: 最近问题")
                .contains("助手: 最近回答").contains("【当前问题】\n继续");
        assertThat(built).doesNotContain("旧问题").doesNotContain("旧回答");
    }

    @Test
    void truncatesLongMessages() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("user", "这是一条很长的历史消息内容"));
        String built = SessionHistoryRebuilder.build(history, "继续", 6, 4);
        assertThat(built).contains("用户: 这是一条…");
    }
}
