# M12 详细设计：工具注册表（Tool Registry）

> 日期：2026-08-15 | 前置：roadmap-m10-m14.md（M12 提前到 M11 前——HITL 的权限级/审批挂点是注册表职责）
> 定位：设计到可开工粒度（类型/迁移步骤/验收门禁），符合 de-risk 纪律——写码前定死。

---

## 1. 目标与范围

- **单一事实源**：14 个工具的名称/描述/权限级/参数规则/执行器统一进注册表——新增工具零改 Harness/PolicyGuard
- **注册表驱动**：PolicyGuard（校验）与 AgentHarness（执行）改为读注册表；MCP 工具列表从注册表生成
- **L3 预留**：注册表承载 accessLevel；L3 工具（dlq.redeliver 等）有元数据无执行器（M11 挂 HITL）
- 明确不做（本期）：工具热插拔/动态注册（运行时），配置化工具文件

## 2. 类型定义（tools 模块，纯类无 Spring）

```java
// tools 模块
public record ToolSpec(
    String name,                // Agent 侧工具名（模型 prompt 使用，如 queryOrderVolume）
    String mcpName,             // MCP 侧工具名（如 order.query_volume；未上 MCP 可 null）
    String description,         // 模型可见描述（SYSTEM_PROMPT 提示候选）
    AccessLevel level,          // L1 业务只读 / L2 观测只读 / L3 高危（M11 审批）
    String schemaJson,          // MCP 参数 schema（JSON 字符串）
    Function<Map<String,String>, String> validator,  // 参数校验：返回错误消息或 null（可 null=无参）
    Function<Map<String,String>, String> invoker     // 执行器（L3 未开放可 null）
) {}

public enum AccessLevel { L1, L2, L3 }

public class ToolRegistry {
    void register(ToolSpec spec);           // 重名覆盖报错
    Optional<ToolSpec> get(String name);    // 按 Agent 侧名（缺失=空）
    Optional<ToolSpec> byMcpName(String mcpName);  // MCP 生成用（缺失=空）
    List<ToolSpec> all();                   // 注册顺序
    int count();                            // 全量（含 L3）
    int usableCount();                      // 可用工具数（level != L3 且 invoker != null；版本追溯用）
}
```

> **Review 修正 1（P0）**：`allowedToolCount` 只统计**可用**工具（L3 无执行器不算）——versionsJson 的 tools 数必须 = 模型实际可用数，否则版本追溯失真。registry 用 `usableCount()`，PolicyGuard 常量方法委托。

**执行器签名统一** `Function<Map<String,String>, String>`（参数全字符串；Harness args 天然是 Map<String,String>，MCP 侧 Object→String 转换）。

## 3. 工具清单（单一事实源，tools 模块 `AgentToolCatalog`）

| Agent 名 | MCP 名 | 级别 | 参数规则 | 执行器来源 |
|---|---|---|---|---|
| queryOrderVolume | order.query_volume | L1 | window | MetricToolAccess |
| paymentSuccessRate | payment.success_rate | L1 | window | MetricToolAccess |
| contentInteraction | content.interaction | L1 | window | MetricToolAccess |
| baselineWindow | baseline.window | L1 | window | MetricToolAccess |
| funnelConversion | funnel.conversion | L1 | window | EventAnalyticsTool |
| paymentFailures | payment.failures | L1 | window | EventAnalyticsTool |
| notePublishEvents | content.publish_events | L1 | window | EventAnalyticsTool |
| httpErrors | service.http_errors | L2 | hours | ObsToolAccess |
| httpLatency | service.http_latency | L2 | hours | ObsToolAccess |
| mqConsumerLag | mq.consumer_lag | L2 | group | ObsToolAccess |
| mqDlqBacklog | mq.dlq_backlog | L2 | group | ObsToolAccess |
| mysqlReplicationLag | mysql.replication_lag | L2 | 无 | ObsToolAccess |
| mysqlDeadlocks | mysql.deadlocks | L2 | 无 | ObsToolAccess |
| logSearch | log.search | L2 | keyword+tailLines | LogSearchAccess |
| dlq.redeliver | mq.dlq_redeliver | **L3** | group+messageId | **M11 挂执行器**（V1 invoker=null）|
| service.restart | — | L3 | service | 预留（invoker=null）|
| order.refund | — | L3 | orderId | 预留（invoker=null）|

> 说明：catalog 只定义**元数据 + 校验规则**（validator 引用 tools 模块现有静态校验）；**执行器由各模块装配**（app 绑桥、mcp 绑纯类）。

## 4. 改造点（按模块，小步）

### 4.1 tools 模块（新增，纯类）
- `ToolSpec` / `AccessLevel` / `ToolRegistry`（见 §2）
- `AgentToolCatalog`：静态清单（§3 表）——name/desc/level/schema/validator 在此定义
- `ToolParamValidators`：从 PolicyGuard 迁移的静态校验（validateWindow/validateHours/validateKeyword/validateTailLines/validateGroup）——**PolicyGuard 的静态方法移入 tools 模块**（app 与 mcp 复用）

### 4.2 app 模块（行为不变重构）
- `AgentToolRegistryConfig`：装配注册表 = catalog.specs + app 侧执行器（lambda 包装 MetricToolAccess/ObsToolAccess/LogSearchAccess 三桥方法）
- `AgentHarness.callTool`：switch 硬编码 → `registry.get(tool)`：
  - 未注册 → `ERROR: 未注册工具 xxx`（现状语义）
  - 注册但 invoker==null（L3 未开放）→ `ERROR: 工具 xxx 未开放执行（L3 需人工审批，V1 不可执行)`（PolicyGuard 先拒，双保险）
  - 正常 → invoker.apply(args)
- `PolicyGuard.evaluate`：静态集合 → 遍历 registry：
  - 未注册 → deny（deny-by-default 语义不变）
  - level==L3 或 invoker==null → requiresApproval（HITL 挂点，V1 恒不通过）
  - 参数校验 → spec.validator()（替换 WINDOW_TOOLS/HOURS_TOOLS 等集合分支；validator==null 表示无参，跳过）
- **PolicyGuard 的 14 个工具名常量保留**（静态 final 不变）——被测试/装配引用，零改动（Review 修正 2）
- `PolicyGuard.allowedToolCount()` → `registry.usableCount()`（版本追溯一致）

### 4.3 mcp 模块（单一事实源落地）
- `McpServerConfig`：14 个 toolSpec 方法 → 循环 registry.all() 生成（schema=spec.schemaJson，执行器=spec.invoker 包装 Object→String + 审计日志）
- schema 常量（WINDOW_SCHEMA 等）移入 catalog（spec.schemaJson）
- **校验职责边界**（Review 修正 3）：参数校验仍在 app 侧 PolicyGuard；MCP 侧保持现状——只做必填参数检查（window/service/keyword/hours），不复制 validator（避免双份校验规则漂移）

### 4.4 SYSTEM_PROMPT
- 工具列表描述仍手写（prompt 是话术不是注册表职责）；注册表描述字段供未来 prompt 生成（V1 不动 prompt）

## 5. 迁移步骤（小步 + 测试安全网）

1. **tools**：ToolSpec/ToolRegistry/AccessLevel/ToolParamValidators + AgentToolCatalog + 单测（注册/查/重名报错/validator 生效）→ tools 全绿
2. **app**：AgentToolRegistryConfig 装配（执行器 lambda 包装三桥）→ PolicyGuard 改注册表驱动（现有 PolicyGuardTest 断言不变）→ Harness.callTool 改注册表（AgentHarnessTest 全绿）
3. **mcp**：McpServerConfig 从注册表生成（McpContractTest 断言 14 工具名不变）
4. 全量回归 200+；真库 E2E 冒烟（工具调用路径不变）

## 6. 验收门禁

- [ ] tools 注册表单测绿（注册/重名/校验/查询）
- [ ] PolicyGuard 注册表驱动后现有断言全绿（行为不变）
- [ ] Harness 工具调用经注册表（AgentHarnessTest 全绿）
- [ ] MCP 工具列表 = 注册表导出（McpContractTest 14 个工具名/参数 schema 不变）
- [ ] 新增工具 30 分钟接入演示：单测内临时注册一条 spec（不污染 catalog，Review 修正 4）→ 证明 Harness/PolicyGuard 零改动即可执行
- [ ] 全量回归绿 + 真库 E2E 冒烟

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| 重构破坏工具调用路径（14 工具 × 2 模块）| 每步小步 + 现有测试兜底（PolicyGuardTest/AgentHarnessTest/McpContractTest 断言不变即行为不变）|
| 执行器签名统一引入转换错误 | 统一 Map<String,String>；MCP 侧 Object→String 单点转换 + 单测 |
| L3 无执行器导致 Harness 误执行 | PolicyGuard 先拒（requiresApproval）+ Harness 侧 invoker==null 二次防御（ERROR 明确文案）|
| 双名（Agent/MCP）映射混乱 | catalog 唯一定义处；byMcpName 返回 Optional + 单测 |
| 注册表 count 语义混入 L3（版本追溯失真）| usableCount() 只算可用（Review 修正 1）|
| PolicyGuard 常量被引用（测试/装配）| 常量保留（静态 final 不变），仅校验逻辑改注册表（Review 修正 2）|

---

> 下一步：写前 review（对照 review-m10-plan 的克制原则）→ 按 §5 顺序实现。
