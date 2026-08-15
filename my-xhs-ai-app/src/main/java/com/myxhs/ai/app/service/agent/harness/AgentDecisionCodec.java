package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AgentDecision JSON 编解码（LLM 结构化输出解析）。
 * 容错：剥离 markdown 代码围栏、提取首个 JSON 对象；解析失败返回 null（由 Harness 反馈重想）。
 */
public class AgentDecisionCodec {

    private final ObjectMapper mapper;

    public AgentDecisionCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 解析模型输出为 AgentDecision；非法返回 null（不抛异常） */
    public AgentDecision parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return null;
        }
        try {
            String json = extractJsonObject(modelOutput);
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, AgentDecision.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从模型输出提取首个 JSON 对象（支持 ```json 围栏、前后杂文本） */
    static String extractJsonObject(String text) {
        String t = text.trim();
        // 剥 markdown 代码围栏
        int fence = t.indexOf("```");
        if (fence >= 0) {
            int end = t.indexOf("```", fence + 3);
            if (end > fence) {
                t = t.substring(fence + 3, end);
            }
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(nl + 1);
            }
            t = t.trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return t.substring(start, end + 1);
    }

    /** 构造反馈消息文本（策略拒绝/证据无效/格式纠正，追加给模型） */
    public static String feedback(AgentDecision decision, String message) {
        return "上一步决策被拒绝：" + message
                + (decision == null || decision.reasoning() == null ? ""
                : "（你的理由：" + decision.reasoning() + "）");
    }

    /** 解析失败时的纠正消息 */
    public static String malformedOutputMessage() {
        return "输出不是合法 JSON，无法解析。必须严格输出 JSON 对象（action 为 TOOL_CALL、ANSWER 或 DECLINE），不要用 markdown 代码块。";
    }

    /** 断言消息是否为 JSON 对象（测试/日志用） */
    JsonNode toJson(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }
}
