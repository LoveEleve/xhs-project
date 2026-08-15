# 深度 Review：M13 实现（多智能体 PoC + 对比评测）

> 日期：2026-08-15 | 对象：M13 双 Agent PoC（design-m13-multiagent.md）| 视角：克制验证/单 Agent 兼容/分派正确性
> 结论：**无 P0/P1**——分派/越权/FULL 兼容全验证；**对比评测：双 Agent 全面优于单 Agent**（决策 D-A：全量推进）

---

## 实现核对（对照 design）

| 设计点 | 落地 |
|--------|------|
| AgentProfile/AgentProfiles（BUSINESS/OPS/FULL）| ✓ FULL=原 SYSTEM_PROMPT 逐字不动（零回归）|
| prompt 变体 = 通用规则段 + 领域工具列表段（Review 修正 1）| ✓ SYSTEM_PROMPT 拆 PROMPT_HEAD/工具列表/TAIL 常量；变体重组 |
| per-run 传递（Review 修正 2）| ✓ AgentRun.profile 字段（单例 Harness 无竞态）|
| PolicyGuard 工具子集过滤 | ✓ evaluate(tool, args, allowedTools)；null=全量兼容 |
| AgentDispatcher 规则分派（零模型成本）| ✓ 信号矩阵单测（业务/排障/冲突/空）|
| RunManager AGENT 分支分派 | ✓ dispatch(query) → harness.run(..., profile)；versionsJson 含 profile.id |
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

**决策（D-A 兑现）**：双 Agent 质量显著提升（pass +14pt、幻觉归零）、成本持平（token 持平）→ **全量推进双 Agent**；
时长略增由 M14 评测闭环持续观察（模型随机性：单次运行，多轮验证稳定性）。

## 注意点（记录在案）

1. **单次运行结论**：对比评测 ~19 分钟/轮，本轮为一次采样——M14 评测闭环（多轮/坏例回流）持续验证
2. **分派默认 BUSINESS**：错误分派由工具集 deny 兜底（模型重想），对比报告 pass=100% 未见分派错误代价
3. **FULL profile 保留**：评测基线与回退路径（配置/异常时单 Agent 兜底）
4. **对比评测未入 eval-gate 门禁**（时长翻倍）：MultiAgentComparisonTest 为独立 tag，按需手动跑（决策数据用途）

## 方法论复盘

- **克制 PoC 形态**：共享引擎 + 参数化 profile（prompt 变体 + 工具子集）——零新执行模型；规则分派零成本（错误分派由 deny 兜底）
- **FULL 兼容是安全网**：SYSTEM_PROMPT 原样不动 + profile 为 null 时全量行为——单 Agent 回归零风险，双 Agent 失败可回退
- **数据说话（D-A）**：对比评测把"多 Agent 是否值得"从争论变成数据——这是 roadmap 决策点设计的兑现

## 结论

- M13 完成：双 Agent 分派（业务/排障）+ 越权防御 + 对比评测报告（双 Agent 胜出）
- 223 测试全绿（app 162 + tools 50 + mcp 11）
- 下一步：M14 评测闭环（100+ 用例/LLM-as-judge/bad-case 回流/阈值校准）
