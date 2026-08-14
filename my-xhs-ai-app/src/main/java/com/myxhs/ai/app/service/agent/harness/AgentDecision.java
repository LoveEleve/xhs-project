package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 模型每步决策（LLM 结构化输出契约，D4）。
 * action：
 *  - TOOL_CALL  调工具（tool+args 必填），进入 VALIDATE/TOOL
 *  - ANSWER     给出最终答案（conclusion/evidenceRefs/counterEvidence/uncertainty）
 *  - ASK        向用户追问（未用，V1 保留）
 * 证据链约束：ANSWER 的 evidenceRefs 必须命中 ToolResultRegistry（Harness 存在性校验，防模型编造）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentDecision(
        String action,
        String tool,
        Map<String, String> args,
        String reasoning,
        String conclusion,
        List<String> evidenceRefs,
        String counterEvidence,
        String uncertainty) {

    /** 仅业务方法，非数据字段（防 Jackson 序列化为 answer/toolCall 属性，破坏快照恢复） */
    @JsonIgnore
    public boolean isToolCall() {
        return "TOOL_CALL".equals(action);
    }

    @JsonIgnore
    public boolean isAnswer() {
        return "ANSWER".equals(action);
    }

    public static AgentDecision toolCall(String tool, Map<String, String> args, String reasoning) {
        return new AgentDecision("TOOL_CALL", tool, args, reasoning, null, null, null, null);
    }

    public static AgentDecision answer(String conclusion, List<String> evidenceRefs,
                                       String counterEvidence, String uncertainty) {
        return new AgentDecision("ANSWER", null, null, null, conclusion, evidenceRefs, counterEvidence, uncertainty);
    }
}
