package com.myxhs.ai.session;

import java.util.List;
import java.util.Map;

/**
 * F7 会话重建：Redis 状态丢失时，用 ai_message 最近对话重建上下文（无工具状态）。
 */
public final class SessionHistoryRebuilder {

    private SessionHistoryRebuilder() {
    }

    /**
     * @param historyAsc 历史消息（时间升序，role=user/assistant）
     * @return 增强后的消息；无历史时原样返回
     */
    public static String build(List<Map<String, Object>> historyAsc, String currentMessage,
                               int maxMessages, int maxChars) {
        if (historyAsc == null || historyAsc.isEmpty()) {
            return currentMessage;
        }
        int from = Math.max(0, historyAsc.size() - Math.max(1, maxMessages));
        StringBuilder sb = new StringBuilder("【历史对话恢复（服务状态丢失，仅供参考）】\n");
        for (int i = from; i < historyAsc.size(); i++) {
            Map<String, Object> row = historyAsc.get(i);
            String role = "assistant".equals(String.valueOf(row.get("role"))) ? "助手" : "用户";
            sb.append(role).append(": ").append(truncate(String.valueOf(row.get("content")), maxChars)).append('\n');
        }
        sb.append("【当前问题】\n").append(currentMessage);
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
