package com.myxhs.ai.app.service.agent.harness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具结果登记簿（§6.1 教训的确定性兜底）：Harness 每次真实工具调用在此登记，
 * evidenceId → 结果。最终答案的存在性校验以它为准——
 * 模型"声称调了工具"但这里无记录 → 判定编造，拒绝。
 */
public class ToolResultRegistry {

    private final Map<String, ToolCallRecord> records = new ConcurrentHashMap<>();

    public record ToolCallRecord(String evidenceId, String tool, Map<String, String> args, String result) {
    }

    /** 登记一次真实工具调用，返回 evidenceId */
    public String register(String tool, Map<String, String> args, String result) {
        String evidenceId = "ev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        records.put(evidenceId, new ToolCallRecord(evidenceId, tool, args, result));
        return evidenceId;
    }

    public boolean contains(String evidenceId) {
        return evidenceId != null && records.containsKey(evidenceId);
    }

    public Optional<ToolCallRecord> get(String evidenceId) {
        return Optional.ofNullable(records.get(evidenceId));
    }

    public int size() {
        return records.size();
    }
}
