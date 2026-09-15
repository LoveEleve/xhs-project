package com.myxhs.ai.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计哈希链（防篡改）：canonical(params) 递归按键排序，哈希输入与实际存储值严格一致。
 * 校验方（scripts/audit-verify.sh）以同样的规范化规则重算。
 */
public final class AuditChain {

    public static final String GENESIS = "GENESIS";
    private static final int RESULT_MAX = 1024;

    private AuditChain() {
    }

    /** 递归规范化：Map 按键排序，List 保持顺序，紧凑输出（与 Python sort_keys+separators 等价） */
    @SuppressWarnings("unchecked")
    public static String canonical(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(normalize(value));
        } catch (Exception e) {
            throw new IllegalStateException("审计参数规范化失败: " + e.getMessage(), e);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditChain::normalize).toList();
        }
        return value;
    }

    public static String truncateResult(String result) {
        if (result == null) {
            return null;
        }
        return result.length() <= RESULT_MAX ? result : result.substring(0, RESULT_MAX);
    }

    /** hash = SHA256(prev | traceId | actor | action | target | canonicalParams | result) */
    public static String hash(String prev, String traceId, Long actor, String action,
                              String target, String canonicalParams, String result) {
        String input = nvl(prev) + "|" + nvl(traceId) + "|" + (actor == null ? "" : actor) + "|"
                + nvl(action) + "|" + nvl(target) + "|" + nvl(canonicalParams) + "|" + nvl(result);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("审计哈希计算失败", e);
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
