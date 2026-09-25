package com.harnessrunner.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class FakeLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        String requirement = extractRequirement(userPrompt);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("summary", "（fake provider）对需求「" + requirement + "」的验收条件草案");
        ArrayNode ac = root.putArray("ac");
        ac.addObject().put("id", "AC-1")
                .put("statement", requirement + " 的主流程可在自动化测试中稳定复现并验证");
        ac.addObject().put("id", "AC-2")
                .put("statement", "边界与异常输入（空值/非法参数）有明确且可验证的预期行为");
        ac.addObject().put("id", "AC-3")
                .put("statement", "失败场景返回可观测的错误信息，且不影响其他既有功能");
        return new LlmResponse(root.toString(), "fake-deterministic", 0, 0, 1L);
    }

    private static String extractRequirement(String userPrompt) {
        if (userPrompt == null) {
            return "未提供需求";
        }
        int marker = userPrompt.indexOf("需求：");
        if (marker < 0) {
            return "未提供需求";
        }
        String tail = userPrompt.substring(marker + 3).strip();
        int newline = tail.indexOf('\n');
        String firstLine = newline > 0 ? tail.substring(0, newline) : tail;
        return firstLine.isBlank() ? "未提供需求" : firstLine.strip();
    }
}
