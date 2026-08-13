package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.mcp.McpToolBridge;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP 桥接验证端点（D2 全链路证明）：Agent 侧 → MCP client(桥) → my-xhs-ai-mcp → 真实工具。
 */
@RestController
@RequestMapping("/api/ai")
public class McpCheckController {

    private final McpToolBridge mcpToolBridge;

    public McpCheckController(McpToolBridge mcpToolBridge) {
        this.mcpToolBridge = mcpToolBridge;
    }

    @PostMapping("/mcp/check")
    public Map<String, String> check(@RequestBody Map<String, String> body) {
        String tool = body.getOrDefault("tool", "");
        String window = body.getOrDefault("window", "");
        try {
            String result = switch (tool) {
                case "order.query_volume" -> mcpToolBridge.queryOrderVolume(window);
                case "payment.success_rate" -> mcpToolBridge.paymentSuccessRate(window);
                case "content.interaction" -> mcpToolBridge.contentInteraction(window);
                default -> throw new IllegalArgumentException("未知工具: " + tool);
            };
            return Map.of("path", "mcp", "result", result);
        } catch (Exception e) {
            return Map.of("path", "mcp", "status", "error",
                    "error", "MCP 调用失败: " + String.valueOf(e.getMessage()));
        }
    }
}
