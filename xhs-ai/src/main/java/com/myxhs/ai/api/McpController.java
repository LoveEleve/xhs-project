package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import com.myxhs.ai.config.McpClientManager;
import com.myxhs.ai.security.AiRoleResolver;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP 接入验证入口（M1.5）
 * <p>GET  /api/ai/mcp/servers               → server 状态
 * <br>GET  /api/ai/mcp/servers/{name}/tools  → 工具清单
 * <br>POST /api/ai/mcp/servers/{name}/tools/{tool} → 直接调用工具</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpClientManager mcpClientManager;
    private final AiRoleResolver roleResolver;

    @GetMapping("/servers")
    public ResponseEntity<R<Map<String, Object>>> servers(jakarta.servlet.http.HttpServletRequest request) {
        if (!roleResolver.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理权限"));
        }
        return ResponseEntity.ok(R.ok(mcpClientManager.status()));
    }

    @GetMapping("/servers/{name}/tools")
    public ResponseEntity<R<List<Map<String, Object>>>> tools(@PathVariable("name") String name,
                                                              jakarta.servlet.http.HttpServletRequest request) {
        if (!roleResolver.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理权限"));
        }
        try {
            return ResponseEntity.ok(R.ok(mcpClientManager.listTools(name)));
        } catch (Exception e) {
            log.error("[MCP] 获取工具清单失败 server={}", name, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.fail(500, "MCP 工具清单获取失败，请稍后重试"));
        }
    }

    @PostMapping("/servers/{name}/tools/{tool}")
    public ResponseEntity<R<McpSchema.CallToolResult>> call(@PathVariable("name") String name,
                                                            @PathVariable("tool") String tool,
                                                            @RequestBody(required = false) Map<String, Object> arguments,
                                                            jakarta.servlet.http.HttpServletRequest request) {
        if (!roleResolver.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理权限"));
        }
        try {
            return ResponseEntity.ok(R.ok(mcpClientManager.callTool(name, tool, arguments)));
        } catch (Exception e) {
            log.error("[MCP] 工具调用失败 server={} tool={}", name, tool, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.fail(500, "MCP 工具调用失败，请稍后重试"));
        }
    }
}
