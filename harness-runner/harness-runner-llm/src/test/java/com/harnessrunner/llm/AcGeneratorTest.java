package com.harnessrunner.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcGeneratorTest {

    private static final String TEMPLATE = "项目: {{PROJECT}}\n需求：\n{{REQUIREMENT}}\n";

    @Test
    void parsesPlainJsonAndRendersPrompt() {
        AcGenerator generator = new AcGenerator((system, user) -> new LlmResponse("""
                {"summary": "概述", "ac": [
                  {"id": "AC-1", "statement": "主流程可验证"},
                  {"id": "AC-2", "statement": "空输入返回错误码"}
                ]}
                """, "test-model", 10, 20, 30L), TEMPLATE);

        AcGeneration generation = generator.generate("my-xhs", "完善读写分离");

        assertEquals(2, generation.draft().criteria().size());
        assertEquals("AC-2", generation.draft().criteria().get(1).id());
        assertEquals("空输入返回错误码", generation.draft().criteria().get(1).statement());
        assertTrue(generation.prompt().contains("完善读写分离"));
        assertTrue(generation.prompt().contains("my-xhs"));
        assertEquals(10, generation.response().promptTokens());
    }

    @Test
    void parsesJsonInsideCodeFence() {
        AcGenerator generator = new AcGenerator((system, user) -> new LlmResponse("""
                ```json
                {"summary": "", "ac": [{"id": "AC-1", "statement": "可测"}]}
                ```
                """, "test-model", 0, 0, 1L), TEMPLATE);

        AcGeneration generation = generator.generate("p", "r");

        assertEquals(1, generation.draft().criteria().size());
    }

    @Test
    void invalidJsonRaisesWithRawResponsePreserved() {
        AcGenerator generator = new AcGenerator((system, user) ->
                new LlmResponse("这不是 JSON", "test-model", 0, 0, 1L), TEMPLATE);

        AcGenerationException exception = assertThrows(AcGenerationException.class,
                () -> generator.generate("p", "r"));

        assertTrue(exception.getMessage().contains("解析失败"), exception.getMessage());
        assertEquals("这不是 JSON", exception.rawResponse());
        assertTrue(exception.prompt().contains("r"));
    }

    @Test
    void clientFailureRaisesWithPromptPreserved() {
        AcGenerator generator = new AcGenerator((system, user) -> {
            throw new LlmException("连接超时");
        }, TEMPLATE);

        AcGenerationException exception = assertThrows(AcGenerationException.class,
                () -> generator.generate("p", "需求X"));

        assertTrue(exception.getMessage().contains("连接超时"), exception.getMessage());
        assertEquals("", exception.rawResponse());
        assertTrue(exception.prompt().contains("需求X"));
    }
}
