package com.myxhs.ai.app.service.agent.harness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 证据链（设计 §6）：每步工具结果/来源追加为一条证据。
 * hash() 为全部证据的语义指纹——供 LoopDetector 判断"过去 M 步是否无新证据"。
 * 去重按语义（tool+window+内容 hash）：重复调用同工具同窗口且结果相同 → 不算新证据（防绕过 no-progress 检测）。
 * 最终答案 = 结论 + 支持证据 + 反证(如有) + 不确定性声明。
 */
public class EvidenceChain {

    public record Evidence(String evidenceId, String tool, String window, String contentHash) {
    }

    private final List<Evidence> entries = new ArrayList<>();

    /** 追加一条证据（语义去重：同 tool+window+内容 或 同 evidenceId 已存在则忽略），返回内容 hash */
    public String add(String evidenceId, String tool, String window, String content) {
        String hash = sha256(Objects.toString(content));
        boolean exists = entries.stream().anyMatch(e ->
                e.evidenceId().equals(evidenceId)
                        || (Objects.equals(e.tool(), tool)
                        && Objects.equals(e.window(), window)
                        && e.contentHash().equals(hash)));
        if (!exists) {
            entries.add(new Evidence(evidenceId, tool, window, hash));
        }
        return hash;
    }

    /** 全链内容指纹（新增语义证据会改变它） */
    public String hash() {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : entries) {
            sb.append(e.evidenceId()).append(':').append(e.contentHash()).append(';');
        }
        return sha256(sb.toString());
    }

    public List<Evidence> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    private static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
