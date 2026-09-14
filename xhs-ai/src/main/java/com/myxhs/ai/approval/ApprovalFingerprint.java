package com.myxhs.ai.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审批原文指纹（RV10 #4）：创建时落库、执行前复核，防审批后篡改原文。
 * 规范化 = 顶层键排序后序列化，保证同一语义 JSON 的指纹稳定。
 */
public final class ApprovalFingerprint {

    private ApprovalFingerprint() {
    }

    public static String of(String rawJson, ObjectMapper objectMapper) throws Exception {
        String json = rawJson == null || rawJson.isBlank() ? "{}" : rawJson;
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        String canonical = objectMapper.writeValueAsString(new TreeMap<>(map));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean matches(String rawJson, String storedHash, ObjectMapper objectMapper) throws Exception {
        if (storedHash == null || storedHash.isBlank()) {
            return true;
        }
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                of(rawJson, objectMapper).getBytes(StandardCharsets.UTF_8));
    }
}
