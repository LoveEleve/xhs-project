package com.harnessrunner.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AcGenerator {

    public static final String SYSTEM_PROMPT =
            "你是 Harness Engineering 的需求分析技能（harnessing）。输出必须是合法 JSON，不要包含任何解释或 Markdown 代码块。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient client;
    private final String template;

    public AcGenerator(LlmClient client) {
        this(client, loadTemplate());
    }

    AcGenerator(LlmClient client, String template) {
        this.client = client;
        this.template = template;
    }

    public AcGeneration generate(String projectId, String requirement) {
        String prompt = template
                .replace("{{PROJECT}}", projectId == null ? "" : projectId)
                .replace("{{REQUIREMENT}}", requirement == null ? "" : requirement);
        LlmResponse response;
        try {
            response = client.complete(SYSTEM_PROMPT, prompt);
        } catch (LlmException e) {
            throw new AcGenerationException("LLM 调用失败: " + e.getMessage(), prompt, "", e);
        }
        try {
            return new AcGeneration(parse(response.content()), prompt, response);
        } catch (RuntimeException e) {
            throw new AcGenerationException("AC 解析失败: " + e.getMessage(), prompt, response.content(), e);
        }
    }

    private AcDraft parse(String content) {
        String json = stripCodeFence(content).strip();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new LlmException("模型输出不是合法 JSON: " + e.getMessage(), e);
        }
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (JsonNode node : root.path("ac")) {
            criteria.add(new AcceptanceCriterion(node.path("id").asText(""), node.path("statement").asText("")));
        }
        return new AcDraft(root.path("summary").asText(""), criteria);
    }

    private static String stripCodeFence(String content) {
        String text = content.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence);
    }

    private static String loadTemplate() {
        try (InputStream input = AcGenerator.class.getResourceAsStream("/prompts/harnessing-ac.md")) {
            if (input == null) {
                throw new IllegalStateException("缺少 prompt 模板: /prompts/harnessing-ac.md");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 prompt 模板失败", e);
        }
    }
}
