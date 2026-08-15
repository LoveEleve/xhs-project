package com.myxhs.ai.tools;

import java.util.Map;
import java.util.function.Function;

/**
 * 工具规格（M12 注册表）：Agent 工具的名称/描述/权限级/参数规则/执行器——单一事实源。
 * - name：Agent 侧工具名（模型 prompt 使用）；mcpName：MCP 侧工具名（未上 MCP 可 null）
 * - validator：参数校验，返回错误消息或 null（null 表示无参工具，跳过校验）
 * - invoker：执行器（参数全字符串 map）；L3 未开放可 null（PolicyGuard 先拒 + Harness 二次防御）
 */
public record ToolSpec(
        String name,
        String mcpName,
        String description,
        AccessLevel level,
        String schemaJson,
        Function<Map<String, String>, String> validator,
        Function<Map<String, String>, String> invoker) {

    public boolean usable() {
        return level != AccessLevel.L3 && invoker != null;
    }
}
