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

    /** 工具错误计数（Agent 启动时注入 MeterRegistry；无注册中心时降级为 no-op） */
    private static volatile io.micrometer.core.instrument.MeterRegistry METER_REGISTRY;

    public static void bindMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        METER_REGISTRY = registry;
    }

    private ToolSupport() {
    }

    public static Mono<ToolResultBlock> result(ToolCallParam param, String text) {
        ToolUseBlock use = param.getToolUseBlock();
        return Mono.just(new ToolResultBlock(use.getId(), use.getName(),
                List.of(TextBlock.builder().text(text).build())));
    }

    public static Mono<ToolResultBlock> error(ToolCallParam param, String message) {
        try {
            if (METER_REGISTRY != null && param != null && param.getToolUseBlock() != null) {
                METER_REGISTRY.counter("ai_tool_errors_total", "tool",
                        param.getToolUseBlock().getName() == null ? "unknown" : param.getToolUseBlock().getName())
                        .increment();
            }
        } catch (Exception ignored) {
        }
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

    public static String traceId(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        if (ctx != null) {
            try {
                return ctx.get("traceId", String.class);
            } catch (Exception ignored) {
            }
        }
        return null;
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

    public static int intArg(ToolCallParam param, String key, int defaultValue) {
        Map<String, Object> input = param.getInput();
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
