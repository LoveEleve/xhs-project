# M13 详细设计：多智能体 PoC（双 Agent 分流 + 评测对比）

> 日期：2026-08-15 | 前置：M12 注册表（工具集参数化的载体）、决策 D-A（PoC 后定：先双 Agent 分流 + 评测对比单 Agent，数据说话）
> 定位：**PoC 最小形态**——共享 Harness 引擎 + 参数化 profile + 规则分派 + 对比评测报告。不新建执行模型。

---

## 1. 目标与范围

- **双 Agent 分流 PoC**：业务归因 Agent（Business）/ 技术排障 Agent（Ops）——共享 AgentHarness 引擎，差异 = prompt 变体 + 工具子集 + 预算
- **对比评测**：同一评测集跑"单 Agent（现状全量）"vs"双 Agent（分派）"→ 报告（通过率/幻觉率/步骤数/token），数据决定全量 or 克制
- 明确不做：多级编排（Orchestrator 调子 Agent）、Agent 间通信、并行子 Agent、动态注册

## 2. 类型与定义（app 模块）

```java
public record AgentProfile(
    String id,               // BUSINESS / OPS
    String systemPrompt,     // 领域化 prompt 变体
    Set<String> toolNames,   // 工具子集（注册表过滤；Agent 只能调用子集内工具）
    AgentBudget budget) {}   // 预算（双 Agent 同预算，V1 不区分）

public class AgentProfiles {
    static final AgentProfile BUSINESS = ...;
    static final AgentProfile OPS = ...;
    static final AgentProfile FULL = ...;  // 单 Agent 现状（全工具）——对比基线
}
```

**工具子集**：
| Agent | 工具（14 可用中）| 领域 |
|-------|----------------|------|
| BUSINESS | queryOrderVolume/paymentSuccessRate/contentInteraction/baselineWindow/funnelConversion/paymentFailures/notePublishEvents | 业务归因（订单/支付/内容/漏斗）|
| OPS | httpErrors/httpLatency/mqConsumerLag/mqDlqBacklog/mysqlReplicationLag/mysqlDeadlocks/logSearch/**dlq.redeliver** | 技术排障（服务/链路/日志/运维动作）|

> 越权防御：Business 请求 httpErrors → 子集外 → PolicyGuard deny（red team 测试点）。
> dlq.redeliver 归 Ops（运维动作语义匹配；L3 审批机制不变）。

**AgentDispatcher（规则分派，零模型成本）**：
```
Ops 强信号：日志/检索/traceId/5xx/错误/延迟/慢/超时/积压/死锁/主从/复制/服务/重启/重投/死信
Business 强信号：订单/支付/内容/互动/漏斗/转化/浏览/加购/退款/销售额/发布
冲突或无信号 → BUSINESS（默认；错误分派代价低：子集外工具被 deny → 模型重想/反馈）
```

## 3. 改造点

| 模块 | 改动 |
|------|------|
| app | `AgentProfile`/`AgentProfiles`（含 FULL 基线）|
| app | `AgentDispatcher`（规则分派；单测覆盖信号矩阵）|
| app | `AgentHarness.run` 增加 profile 参数（重载，默认 FULL 兼容现有调用）——systemPrompt 用 profile；PolicyGuard 校验加工具子集过滤 |
| app | `PolicyGuard.evaluate` 加可选 allowedTools 参数（null=全量；子集外 deny）|
| app | `RunManager.submit`：AGENT 分支 → dispatcher.dispatch(query) → harness.run(..., profile)；versionsJson 含 profile.id |
| app | `EvalRunner` 支持 profile（对比评测：同评测集跑 FULL vs 分派）|
| test | `MultiAgentComparisonTest`（eval-gate tag，真库）：分派正确性 + 越权 deny + 对比报告 JSON |
| 前端 | 无（V1 薄壳不展示 Agent 标识；事件流不变）|

> **Review 修正 1（P0）**：profile 差异 = **工具列表段 + 领域强调段**，通用规则段（数字来自工具/基线计算/收敛/JSON 格式）两 profile 共用——V1 手写变体文本（不动 FULL 的 SYSTEM_PROMPT，单 Agent 行为零变化）；从注册表生成工具列表属 M14 prompt 治理。
> **Review 修正 2（P0）**：profile 必须 **per-run 传递**（`AgentRun` 加 profile 字段）——AgentHarness 是单例（executor 20 线程并发 run），存实例字段是竞态。
> **Review 修正 3（P1）**：EvalRunner 加 `run(cases, profile)` 重载；对比测试（MultiAgentComparisonTest）用锚点子集调两次，产出对比报告。

## 4. 核心机制

- **分派点**：RunManager.submit 的 AGENT 分支（意图已定，领域分派零模型成本——不引 LLM 分派，错误分派由工具集 deny 兜底）
- **工具集过滤**：PolicyGuard 校验 `spec ∈ allowedTools`（在 deny-by-default 前）；子集外工具提示模型"该工具不可用（当前 Agent 仅限 X 领域工具）"——模型重想正确工具
- **prompt 变体**：Business 强调业务指标归因（漏斗/窗口对比）；Ops 强调服务链路排障（观测/日志/运维动作）——差异 = 工具列表段 + 领域强调段；**通用规则段共用**（Review 修正 1）
- **per-run 传递**：AgentRun 携带 profile（PolicyGuard 校验依据），非单例字段（Review 修正 2）
- **预算**：双 Agent 同预算（V1；对比报告看 avgSteps 决定是否调整）
- **单 Agent 兼容**：FULL profile = 现状行为（prompt 不变、全工具）——单 Agent 回归零风险

## 5. 对比评测（M13 门禁）

```
MultiAgentComparisonTest（@Tag("eval-gate")，真库+真模型，~25 分钟）：
  case 集 = eval/cases.yaml 锚点（6 场景 + 拒答，与 EvalGateRunTest 同源）
  跑两次：FULL（单 Agent）/ DISPATCH（双 Agent 分派）
  对比指标：passRate / hallucinationRate / completionRate / avgSteps / avgTokens / avgDuration
  产出：target/multi-agent-comparison.json（含分派矩阵：每 case → 分派到哪个 Agent）
结论决策：双 Agent 无退化（passRate ≥ 单 Agent）且成本下降（avgTokens/steps 降低）→ 全量；
  否则记录"单 Agent 已够 + 何时需要多 Agent"的克制论证
```

## 6. 验收门禁

- [ ] 分派正确性：业务问 → BUSINESS（子集含 queryOrderVolume）；排障问 → OPS（子集含 httpErrors/logSearch）；无信号 → BUSINESS
- [ ] 越权防御：Business 请求 httpErrors → deny（red team）；OPS 请求订单工具 → deny
- [ ] 单 Agent 行为不变：FULL profile 跑现有测试全绿（回归零风险）
- [ ] 对比评测报告产出（多轮/归因/排障/拒答场景，分派矩阵 + 指标对比）
- [ ] 全量回归绿 + 真库冒烟

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| 分派错误导致工具缺失（如业务问带日志语义）| 子集外 deny 提示模型换工具/重想；对比报告暴露分派准确率 |
| prompt 变体引入行为回归 | FULL profile = 原 prompt（对比基线）；双 Agent 只用变体 |
| 评测时长翻倍（FULL + DISPATCH）| 锚点子集（7 条）而非全量 18 条；对比报告为一次性产出 |
| 多 Agent 是过度设计（诊断场景短会话）| 对比报告数据说话；若无效 → 克制论证入档（roadmap D-A 已定）|

---

> 下一步：写前 review（对照克制原则：共享引擎/规则分派/对比评测）→ 实现。
