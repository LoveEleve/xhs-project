# 设计：Agent Harness（核心引擎）详细设计

> 版本：v0.1 | 日期：2026-08-10 | 归属：`my-xhs-ai` 核心
> 定位：把 DAD §2.2 的 Agent 循环**深化为可实现的引擎设计**——循环状态机、预算、循环检测、HITL、证据链、Step 粒度。
> 关联：PLAN §6.1（韧性清单）、DAD §3（Run/Step 模型）、learning-path L4。

---

## 1. 目标与非目标
- **目标**：一个受限、可恢复、可审计的多步调查引擎——Agent 逐步决定"查什么→用什么工具→看结果→再决定"，直到归因或资源用尽。
- **非目标**：不做无限自由对话；不做任意代码执行（CodeAct，见词典红线）；不做写工具（V1）。

---

## 2. 循环状态机（每 Step 内）
```
STEP_START
  ├─ THINK    模型决定下一步（要调的工具+参数+理由）──┐
  ├─ VALIDATE PolicyGuard 校验：工具在授权集？参数合法？←─┤
  │     ├─ 拒绝 → 记录 policy_denied → 反馈模型重想（计入预算）
  │     └─ 通过 ↓
  ├─ TOOL     MCP 调用（带 deadline）
  │     ├─ 成功 → 结果回填
  │     ├─ 可重试错误 → 退避重试（预算内）
  │     ├─ 不可重试 → 失败，模型重想
  │     └─ 超时 → 失败
  ├─ OBSERVE  模型评估结果：归因？证据/反证？下一步？
  ├─ LOOPCHECK 连续同工具/同状态检测 + 预算检查
  │     ├─ 疑似死循环 → 终止
  │     └─ 预算尽 → 终止(partial)
  └─ 决定：ANSWER(结束) / 再 THINK / HITL(等审批) / CANCEL
```
> 每个状态都落一个 Step 记录（checkpoint 粒度）。

---

## 3. 预算控制（三重封顶）
| 预算 | 上限 | 触发动作 |
|------|------|---------|
| 步骤数 maxSteps | 如 15 | 达到 → 停止，返回 partial |
| Token maxTokens | 如 30k | 接近 → 压缩上下文/停止 |
| 成本 maxCost | 如 ¥x | 达到 → 停止并告警 |
- 由 `LoopCtrl` 单调累加，**每个 THINK/TOOL 前检查**，不靠事后。

---

## 4. 循环检测算法（防死循环）
检测两种模式，任一命中即终止：
1. **重复工具 + 相同参数**：连续 N 次（如 3 次）调用同一工具且关键参数相同 → 停。
2. **状态不前进**：过去 M 步（如 6 步）内未新增任何"新证据/新结论"（用证据链 hash 判断）→ 停。
- 命中后：记录 `loop_detected`，返回 partial + 已收集证据，不无限转。

---

## 5. HITL 人工审批门（高危动作）
- 触发：PolicyGuard 判定工具属于 **L3**（重启/重投/回滚/写）→ 一律暂停。
- 流程：Run 状态 → `WAITING_APPROVAL` → SSE `run.waiting_approval` → UI 按钮 → `POST /api/runs/{id}/approve{decision,reason}`。
- 未审批不执行；审批拒绝则终止；审批全程审计（谁/何时/理由）。
- V1 无 L3 工具开放，但 Harness 保留此门（为 D5 演示 + 安全纵深）。

---

## 6. 证据链（Evidence Chain）
- 每步 `evidenceRefs[]`：指向该结论依赖的工具结果/检索条目/来源。
- 最终答案必须：**结论 + 支持证据 + 反证(如有) + 不确定性声明**。
- 反证：若存在不支持结论的数据，Agent 须显式提及，不隐藏。
- UI 上每条证据可点回原文（口径/来源引用）。

---

## 7. Step 粒度 Checkpoint（配合 RunManager）
- **Step = checkpoint 单位**：每完成一个 Step 即持久化（Run Store）。
- Worker 崩溃 → 重启后从**最后一个未完成 Step 重放**，不重跑已完成步骤。
- 幂等：工具调用带幂等键，重复消息不产生重复副作用（V1 只读工具，副作用天然小）。

---

## 8. 并发/执行模型
- **同步执行 + SSE 流式推送**：一次 Run 单线程顺序执行，事件实时推给 UI（差分渲染，参考 pi TUI）。
- **异步提交**：`POST /api/runs` 立即返回 runId，执行在后台；SSE 订阅进度。
- 并发上限：内部工具，**中等并发**（如 20 并发）即可，不做大规模高并发（企业姿态见下方决策）。

---

## 9. 错误分类与韧性接线（对接 §6.1）
| 错误 | 分类 | 处理 |
|------|------|------|
| 模型超时/不可用 | retryable | ModelProvider 重试/降级（§6.1#3）|
| 工具超时 | retryable(有限) | deadline + 重试/放弃（#4）|
| 工具可重试错误 | retryable | 退避重试（#5）|
| 工具不可重试错误 | non_retryable | 反馈模型重想（#5）|
| 策略拒绝 | policy_denied | 记录 + 反馈重想（#14）|
| Worker 崩溃 | recovery | checkpoint replay（#8）|
| 预算尽 | terminal | partial（#2）|

---

## 10. 依赖与输入
- **输入**：Run 定义（type/query/budget/userId/roles）+ IntentRouter 判定走 AgentPath。
- **依赖**：ModelProvider（LangChain4j）、MCP 工具层、RunManager、Guardrails、TraceService、AuditService。
- **边界**：Harness 只做"决策与编排"，不持有业务数据；数据全走 MCP 工具（安全内建在工具层，ADR-005）。

---

## 11. 待验证点（D4 前 PoC）
- LangChain4j 的 ReAct 循环是否够用 vs 手写 Harness 的取舍（PLAN §4 框架对照）。
- 循环检测参数（N/M 阈值）需用评测集校准，防误杀/漏杀。
- 预算默认值需按真实分布设定。
