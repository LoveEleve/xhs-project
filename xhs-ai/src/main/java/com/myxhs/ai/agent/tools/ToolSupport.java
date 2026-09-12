package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 工具公共支撑（结果构造/上下文提取；工具 never-throw 契约）
 */
public final class ToolSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSupport() {
    }

    public static Mono<ToolResultBlock> result(ToolCallParam param, String text) {
        ToolUseBlock use = param.getToolUseBlock();
        return Mono.just(new ToolResultBlock(use.getId(), use.getName(),
                List.of(TextBlock.builder().text(text).build())));
    }

    public static Mono<ToolResultBlock> error(ToolCallParam param, String message) {
        return result(param, "{\"error\":\"" + escape(message) + "\"}");
    }

    public static Long actor(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        if (ctx != null && ctx.getUserId() != null) {
            try {
                return Long.parseLong(ctx.getUserId());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    public static String sessionId(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        return "default";
    }

    public static String arg(ToolCallParam param, String key) {
        Map<String, Object> input = param.getInput();
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json serialize failed\"}";
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
