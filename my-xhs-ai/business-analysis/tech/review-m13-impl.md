# 深度 Review：M13 实现（多智能体 PoC + 对比评测）

> 日期：2026-08-15 | 对象：M13 双 Agent PoC（design-m13-multiagent.md）| 视角：克制验证/单 Agent 兼容/分派正确性/与既有机制组合
> 结论：**1 个 P0 + 1 个 P1（二轮 review 发现并修复）**——分派/越权/FULL 兼容全验证；**对比评测：双 Agent 全面优于单 Agent**

---

## P0-1（二轮 review）：纯 traceId 输入被分派到 BUSINESS——破坏 M9-1 查日志闭环

**问题链**：IntentRouter L0 的 traceId 强信号（`^[a-f0-9]{32}$`）→ AGENT → AgentDispatcher 对纯 hex 无任何领域词 → **BUSINESS**（无 logSearch）→ 模型无法查日志——M9-1 traceId 查询闭环被分派破坏。

**修复**：dispatcher 加 `TRACE_ID_PATTERN`（与 IntentRouter 同规则）→ OPS。回归测试：纯 hex → OPS。

## P1-1（二轮 review）：resume 不恢复画像——审批/崩溃恢复后工具子集过滤失效

**问题链**：resume 重建 AgentRun（profile=null）→ 审批恢复的 Ops run 恢复后模型可调用业务工具（子集过滤丢失）——双 Agent 的越权边界在恢复路径被绕过。

**修复**：resume 从 versionsJson 解析 profile.id → `AgentProfiles.byId` 恢复画像。回归测试：Ops 挂起 → approve → resume → profile==OPS。

## 实现核对（对照 design）

| 设计点 | 落地 |
|--------|------|
| AgentProfile/AgentProfiles（BUSINESS/OPS/FULL）| ✓ FULL=原 SYSTEM_PROMPT 逐字不动（零回归）|
| prompt 变体 = 通用规则段 + 领域工具列表段（Review 修正 1）| ✓ SYSTEM_PROMPT 拆 PROMPT_HEAD/工具列表/TAIL 常量；变体重组 |
| per-run 传递（Review 修正 2）| ✓ AgentRun.profile 字段（单例 Harness 无竞态）|
| PolicyGuard 工具子集过滤 | ✓ evaluate(tool, args, allowedTools)；null=全量兼容 |
| AgentDispatcher 规则分派（零模型成本）| ✓ 信号矩阵单测（业务/排障/冲突/空/纯 hex）|
| RunManager AGENT 分支分派 | ✓ AiQueryController 同走 submit（无两入口分裂）|
| EvalRunner per-case profileFor | ✓（对比评测单测用）|
| 越权防御 | ✓ HarnessCoreTest：Business 请求观测工具 deny / Ops 请求业务工具 deny / 子集外 L3 deny |

## 对比评测结果（真库+真模型，锚点 7 条）

| 指标 | 单 Agent（FULL）| 双 Agent（分派）| 结论 |
|------|:--:|:--:|------|
| 通过率 | 85.7% | **100.0%** | +14.3pt |
| 幻觉率 | 14.3% | **0.0%** | 归零 |
| avgSteps | 11.6 | 13.0 | 略增 |
| avgTokens | 19111 | 19136 | 持平 |
| avgDuration | 72.8s | 92.9s | 增 28%（步骤略多所致）|

**决策（D-A 兑现）**：双 Agent 质量显著提升（pass +14pt、幻觉归零）、成本持平（token 持平）→ **全量推进双 Agent**。

## 注意点（记录在案）

1. **单次运行结论**：对比评测 ~19 分钟/轮，本轮为一次采样——M14 评测闭环（多轮/坏例回流）持续验证
2. **分派默认 BUSINESS**：错误分派由工具集 deny 兜底；**领域冲突（业务词+排障词）→ BUSINESS**（单测锁定）
3. **FULL profile 保留**：评测基线与回退路径（配置/异常时单 Agent 兜底）
4. **P2：会话跨 Agent 切换**（第一问 Ops 第二问 Business）：历史注入为结论无工具原文，工具子集变化无碍（每问独立分派）；记录观察
5. **对比评测未入 eval-gate 门禁**（时长翻倍）：MultiAgentComparisonTest 独立 tag，按需手动跑

## 方法论复盘

- **二轮 review 的增量价值**：分派是"新输入路径"，必须对**既有强信号输入**做回归（traceId 纯 ID 是无领域词但有明确语义的输入——"无信号默认 BUSINESS"对它是错的）；恢复路径是"第二条执行路径"，分派边界必须两端一致（resume 恢复画像）
- **克制 PoC 形态**：共享引擎 + 参数化 profile——零新执行模型；规则分派零成本（错误分派由 deny 兜底）
- **FULL 兼容是安全网**：SYSTEM_PROMPT 原样不动 + profile=null 全量行为——单 Agent 回归零风险

## 结论

- M13 完成（含二轮 review P0/P1 修复）：双 Agent 分派 + 越权防御 + 对比评测报告（双 Agent 胜出）
- 225 测试全绿（app 164 + tools 50 + mcp 11）
- 下一步：M14 评测闭环（100+ 用例/LLM-as-judge/bad-case 回流/阈值校准）
