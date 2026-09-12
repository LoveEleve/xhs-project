package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSupport：工具结果 JSON 契约（含控制字符转义）
 */
class ToolSupportTest {

    private ToolCallParam param() {
        ToolCallParam param = mock(ToolCallParam.class);
        ToolUseBlock use = mock(ToolUseBlock.class);
        when(param.getToolUseBlock()).thenReturn(use);
        when(use.getId()).thenReturn("call-1");
        when(use.getName()).thenReturn("dlq_topic_list");
        return param;
    }

    @Test
    void errorResultIsValidJsonEvenWithControlChars() throws Exception {
        ToolResultBlock block = ToolSupport.error(param(), "line1\nline2 \"quoted\"\tend\\tail").block();
        assertEquals("call-1", block.getId());
        assertEquals("dlq_topic_list", block.getName());

        String text = ((TextBlock) block.getOutput().get(0)).getText();
        JsonNode node = new ObjectMapper().readTree(text);
        assertEquals("line1\nline2 \"quoted\"\tend\\tail", node.get("error").asText());
    }

    @Test
    void resultSerializesJsonPayload() throws Exception {
        ToolResultBlock block = ToolSupport.result(param(), ToolSupport.json(Map.of("backlog", 7))).block();
        String text = ((TextBlock) block.getOutput().get(0)).getText();
        assertEquals(7, new ObjectMapper().readTree(text).get("backlog").asInt());
    }

    @Test
    void schemaDeclaresTypePropertiesAndRequired() {
        Map<String, Object> schema = ToolSupport.schema(
                Map.of("group", Map.of("type", "string")), List.of("group"));
        assertEquals("object", schema.get("type"));
        assertTrue(schema.containsKey("properties"));
        assertEquals(List.of("group"), schema.get("required"));
    }
}
