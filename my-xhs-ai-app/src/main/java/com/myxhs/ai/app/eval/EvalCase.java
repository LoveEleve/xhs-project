package com.myxhs.ai.app.eval;

import java.util.List;
import java.util.Map;

/**
 * 评测用例（M6：YAML 定义，机器可读）。
 * 断言分层：
 *  - 硬断言（确定性）：statusIn / minEvidence / contains / notContains
 *  - 软断言（质量）：numbersConsistent（答案数字与工具证据一致性——幻觉率检测）
 */
public record EvalCase(
        String id,
        String query,
        List<String> tags,
        List<String> statusIn,
        int minEvidence,
        List<String> contains,
        List<String> anyContains,
        List<String> notContains,
        List<String> notRegex,
        boolean numbersConsistent) {

    public static EvalCase fromYaml(Map<String, Object> m) {
        return new EvalCase(
                str(m.get("id")),
                str(m.get("query")),
                strList(m.get("tags")),
                strList(m.get("statusIn")),
                intOf(m.get("minEvidence")),
                strList(m.get("contains")),
                strList(m.get("anyContains")),
                strList(m.get("notContains")),
                strList(m.get("notRegex")),
                boolOf(m.get("numbersConsistent")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolOf(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
