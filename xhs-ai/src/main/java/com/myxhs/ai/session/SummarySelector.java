package com.myxhs.ai.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 摘要块选择：保留最近 keepRecent 条不动，把更早且未摘要的部分交给模型压缩。
 */
public final class SummarySelector {

    private SummarySelector() {
    }

    /** @return 待摘要消息（升序）；不足 minBatch 时返回空列表 */
    public static List<Map<String, Object>> pickBlock(List<Map<String, Object>> orderedAsc,
                                                      long uptoMessageId, int keepRecent, int minBatch) {
        List<Map<String, Object>> older = new ArrayList<>();
        int end = Math.max(0, orderedAsc.size() - Math.max(0, keepRecent));
        for (int i = 0; i < end; i++) {
            Map<String, Object> row = orderedAsc.get(i);
            long id = ((Number) row.get("id")).longValue();
            if (id > uptoMessageId) {
                older.add(row);
            }
        }
        return older.size() >= minBatch ? older : List.of();
    }
}
