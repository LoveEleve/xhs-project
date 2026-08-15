package com.myxhs.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12 工具注册表单测：注册/查询/重名报错/可用数/validator 生效。
 */
class ToolRegistryTest {

    @Test
    void 注册查询与顺序() {
        ToolRegistry reg = new ToolRegistry();
        List<ToolSpec> specs = AgentToolCatalog.specs();
        specs.forEach(reg::register);

        assertEquals(specs.size(), reg.count());
        assertEquals(specs.size(), reg.all().size());
        // 顺序稳定（MCP 工具列表依赖）
        assertEquals(specs.get(0).name(), reg.all().get(0).name());
        // Agent 名 + MCP 名双向查询
        assertTrue(reg.get(AgentToolNames.QUERY_ORDER_VOLUME).isPresent());
        assertTrue(reg.byMcpName("order.query_volume").isPresent());
        assertEquals(AgentToolNames.QUERY_ORDER_VOLUME,
                reg.byMcpName("order.query_volume").orElseThrow().name());
        assertTrue(reg.get("nonexistent").isEmpty());
    }

    @Test
    void 重名注册报错() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(AgentToolCatalog.specs().get(0));
        assertThrows(IllegalStateException.class,
                () -> reg.register(AgentToolCatalog.specs().get(0)), "重名必须报错");
    }

    @Test
    void 可用数与L3区分() {
        ToolRegistry reg = new ToolRegistry();
        AgentToolCatalog.specs().forEach(reg::register);
        // catalog 是元数据（invoker=null）：17 条全不可用——执行器由模块装配时绑定
        assertEquals(17, reg.count());
        assertFalse(reg.all().stream().anyMatch(ToolSpec::usable));
        assertTrue(reg.get(AgentToolNames.L3_DLQ_REDELIVER).orElseThrow().level() == AccessLevel.L3);
        // 模拟 app 装配：14 个可用工具绑执行器（30 分钟接入演示：零改 Harness/PolicyGuard）
        ToolRegistry wired = new ToolRegistry();
        for (ToolSpec s : AgentToolCatalog.specs()) {
            if (s.level() != AccessLevel.L3 && s.mcpName() != null) {
                wired.register(new ToolSpec(s.name(), s.mcpName(), s.description(), s.level(),
                        s.schemaJson(), s.validator(), args -> "ok:" + s.name()));
            } else {
                wired.register(s);
            }
        }
        assertEquals(14, wired.usableCount(), "装配执行器后 14 可用");
        assertEquals(17, wired.count());
        // 14 个可用工具均有 mcpName（MCP 契约依赖）
        assertTrue(wired.all().stream().filter(ToolSpec::usable).allMatch(s -> s.mcpName() != null));
    }

    @Test
    void validator按参数规则校验() {
        ToolRegistry reg = new ToolRegistry();
        AgentToolCatalog.specs().forEach(reg::register);
        // window 工具：合法/非法
        var windowSpec = reg.get(AgentToolNames.QUERY_ORDER_VOLUME).orElseThrow();
        assertNotNull(windowSpec.validator());
        assertTrue(windowSpec.validator().apply(Map.of("window", "2026-08-01~2026-08-07")) == null);
        assertTrue(windowSpec.validator().apply(Map.of("window", "bad-window")) != null);
        // hours 工具
        var hoursSpec = reg.get(AgentToolNames.HTTP_ERRORS).orElseThrow();
        assertTrue(hoursSpec.validator().apply(Map.of("hours", "24")) == null);
        assertTrue(hoursSpec.validator().apply(Map.of("hours", "999")) != null);
        assertTrue(hoursSpec.validator().apply(Map.of()) != null, "hours 必填");
        // logSearch：keyword 白名单 + tailLines 范围
        var logSpec = reg.get(AgentToolNames.LOG_SEARCH).orElseThrow();
        assertTrue(logSpec.validator().apply(Map.of("keyword", "ERROR", "tailLines", "100")) == null);
        assertTrue(logSpec.validator().apply(Map.of("keyword", "ERROR; rm -rf")) != null, "shell 语义拒绝");
        assertTrue(logSpec.validator().apply(Map.of("keyword", "ERROR", "tailLines", "99999")) != null);
        // 无参工具：validator=null
        var noArg = reg.get(AgentToolNames.MYSQL_DEADLOCKS).orElseThrow();
        assertTrue(noArg.validator() == null);
    }
}
