package com.myxhs.ai.app.eval;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * LLM-as-judge（M14）：对 finalAnswer 主观质量评分 0-5。
 * 维度：结论完整性 / 表述质量 / 不确定性声明 / 结构清晰——主观质量，不含证据一致性
 * （那是 EvalAsserter 的确定性职责——不把确定性检查交给模型判断）。
 * 成本控制：答案截断 2000 字符；开关配置（默认关，nightly 开）。
 */
public class EvalJudge {

    private static final int ANSWER_MAX_LEN = 2000;

    private final ChatModel judgeModel;
    private final boolean enabled;

    public EvalJudge(ChatModel judgeModel, boolean enabled) {
        this.judgeModel = judgeModel;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 评分 0-5；未启用/模型失败 → -1（报告标记 not-scored） */
    public double score(String query, String answer) {
        if (!enabled || judgeModel == null) {
            return -1;
        }
        String truncated = answer == null ? "" : answer;
        if (truncated.length() > ANSWER_MAX_LEN) {
            truncated = truncated.substring(0, ANSWER_MAX_LEN) + "…";
        }
        try {
            String prompt = """
                    你是评测员。对诊断 Agent 的回答做主观质量评分（0-5，可 0.5 步进），只输出数字。
                    评分维度：
                    - 结论完整性：是否正面回答用户问题、归因是否充分（0-2 分权重最高）
                    - 不确定性声明：是否如实说明证据不足/无法确认（不瞎编）
                    - 表述质量：是否结构化、无矛盾、无冗余
                    - 证据引用：结论是否与引用的证据链对得上（仅文本层面）
                    用户问题：%s
                    Agent 回答：
                    %s
                    输出格式：只输出数字（如 4.5）""".formatted(query, truncated);
            ChatResponse resp = judgeModel.chat(ChatRequest.builder()
                    .messages(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(prompt)))
                    .build());
            String text = resp.aiMessage() == null ? "" : resp.aiMessage().text();
            if (text == null || text.isBlank()) {
                return -1;
            }
            String token = text.trim().split("[\\s，,。：:]+")[0];
            double v = Double.parseDouble(token);
            return Math.max(0, Math.min(5, v));
        } catch (Exception e) {
            return -1;
        }
    }
}
