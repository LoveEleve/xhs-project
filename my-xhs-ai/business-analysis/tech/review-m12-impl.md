# 深度 Review：M12 实现（工具注册表）

> 日期：2026-08-15 | 对象：M12 工具注册表实现（design-m12-tool-registry.md）| 视角：契约保持/单一事实源/克制
> 结论：**无 P0/P1**——2 个注意点记录在案，209 测试全绿 + 真库冒烟

---

## 实现核对（对照 design）

| 设计点 | 落地 |
|--------|------|
| ToolSpec/ToolRegistry/AccessLevel（tools 纯类）| ✓ 含 usable()/usableCount()（Review 修正 1：版本追溯只算可用 14）|
| AgentToolCatalog 单一事实源（17 条：14 可用 + 3 L3）| ✓ 描述/schema/validator 唯一定义处 |
| AgentToolBinder（执行器绑定三接口，app/mcp 同源）| ✓ 新增工具 = catalog + binder 各一条 |
| PolicyGuard 注册表驱动 | ✓ 常量保留（引用点零改动）；L3/invoker==null → requiresApproval |
| AgentHarness.callTool 注册表 | ✓ 未注册/无执行器 → ERROR 明确文案（双保险）|
| MCP 工具列表 = 注册表导出 | ✓ 14 工具上线（L3 不上）；必填参数来自 schemaJson.required |
| 30 分钟接入演示 | ✓ ToolRegistryTest 模拟装配（零改 Harness/PolicyGuard）|

## 注意点（记录在案，非缺陷）

1. **MCP 工具顺序是既有契约**：McpContractTest 锁定 14 个工具名/顺序/schema——catalog 顺序即 MCP 顺序（log.search 排观测首位）。新增工具加在合适位置，勿随意插队破坏契约断言。
2. **注册表两份实例**：AgentHarness 构造器内部装配一份（PolicyGuard/callTool 用）+ AgentToolRegistryConfig bean 一份（测试/审计用）——同 catalog 同源，无共享状态，可接受。若未来需要统一实例（如跨实例共享状态），从 Config 注入即可。

## 方法论复盘

- **契约先行**：McpContractTest 的"顺序断言"在重构中保护了对外契约（14 工具名/顺序/schema 零变化）——注册表化是**内部重构**，对外不可见，这是正确的落地方式
- **装配点收敛**：执行器绑定集中在 AgentToolBinder 一处（app/mcp 复用），新增工具只改 2 处（catalog + binder）——比"零改"更务实（执行器映射必须显式）
- **测试零改动策略**：AgentHarness 构造器内部自动装配注册表 → 35 个既有 Harness 测试全部原样通过（行为不变的最强证明）

## 结论

- M12 完成：注册表驱动落地，209 测试全绿，真库 E2E 冒烟通过（15 条证据全链路正常）
- 下一步：M11 HITL（dlq.redeliver 挂注册表：catalog 加执行器 + WAITING_APPROVAL 状态机）
