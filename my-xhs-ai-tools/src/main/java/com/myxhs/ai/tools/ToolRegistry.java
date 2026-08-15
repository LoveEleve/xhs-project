package com.myxhs.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具注册表（M12）：名称 → ToolSpec（元数据 + 校验 + 执行器）。
 * 单一事实源：PolicyGuard 校验 / AgentHarness 执行 / MCP 工具列表均读此注册表。
 * 重名注册报错（防静默覆盖）；按注册顺序返回（MCP 工具列表顺序稳定）。
 */
public class ToolRegistry {

    private final ConcurrentHashMap<String, ToolSpec> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ToolSpec> byMcpName = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ToolSpec> ordered = new CopyOnWriteArrayList<>();

    public void register(ToolSpec spec) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        ToolSpec prev = byName.putIfAbsent(spec.name(), spec);
        if (prev != null) {
            throw new IllegalStateException("工具重名注册: " + spec.name());
        }
        if (spec.mcpName() != null && !spec.mcpName().isBlank()) {
            ToolSpec dup = byMcpName.putIfAbsent(spec.mcpName(), spec);
            if (dup != null) {
                byName.remove(spec.name());
                throw new IllegalStateException("MCP 工具名重复: " + spec.mcpName());
            }
        }
        ordered.add(spec);
    }

    public Optional<ToolSpec> get(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    public Optional<ToolSpec> byMcpName(String mcpName) {
        return Optional.ofNullable(mcpName == null ? null : byMcpName.get(mcpName));
    }

    public List<ToolSpec> all() {
        return new ArrayList<>(ordered);
    }

    /** 全量（含 L3 预留） */
    public int count() {
        return ordered.size();
    }

    /** 可用工具数（level != L3 且 invoker != null；版本追溯/模型可用数用） */
    public int usableCount() {
        return (int) ordered.stream().filter(ToolSpec::usable).count();
    }
}
