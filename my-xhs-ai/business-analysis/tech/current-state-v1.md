# 项目现状梳理 v1（2026-08-15 快照）

> 用途：后续规划的输入基线。所有数字以 2026-08-15 实测为准（不写死，随演进更新）。

---

## 1. 代码规模与结构

| 模块 | main(行) | test(行) | 职责 |
|------|:--:|:--:|------|
| my-xhs-ai-tools | 1344 | 802 | 共享工具纯类：业务指标 ×3（order/payment/content）+ 事件流水 ×3 + baseline + 观测 ×2 + MQ ×2 + MySQL ×2 + **log.search** + ToolJson/窗口/白名单 |
| my-xhs-ai-app | 5093 | 3304 | AI 核心：路由/AgentHarness/RunManager/RunStore/Conversation(规划)/评测/指标/SSE |
| my-xhs-ai-mcp | 438 | 306 | MCP 服务：14 工具（Streamable HTTP，认证+审计）|
| frontend/src/pages/ai + api | 659 | — | AI 诊断台薄壳（SSE 消费/证据链 Drawer/取消/?run= 直开）|

**测试**：30 个测试类，192 个 @Test（6 个集成测试无凭据自动跳过）。
**依赖**：Boot 3.2.5 + JDK17 + LangChain4j 1.0.0（手工集成）+ MCP SDK 0.18.3 + Jackson（根 BOM）。

## 2. 能力现状（已交付）

| 能力面 | 内容 | 深度 |
|--------|------|------|
| A 业务诊断 | 订单量下降/支付成功率/内容互动（A1-A3）| ✅ 工具闭环（事件表 0 行，链路可用）|
| B 技术排障 | 5xx/延迟/MQ 积压/死锁/主从（B2-B4）| ✅ 真实 Prometheus/MySQL |
| 查日志 | log.search 受控检索（M9-1）| ✅ 白名单+无 shell+4 重截断 |
| 意图路由 | 三阶：L0 规则 → L1 **LLM 分类（主）** → L2 语义降级 → L3 默认引导 | ✅ 2026-08-15 重构完成 |
| 拒答机制 | DECLINE 零证据豁免（防凑证据编造）| ✅ 机制级 |
| Durable | Run Store checkpoint/心跳/崩溃恢复/协作取消/SSE 单消费者+断开释放 | ✅ M5 + 修复 |
| 可追溯 | 历史 run store 回退（fromStore）/ finalAnswer 落库 / 全量 run 落库 | ✅ M8-4 闭环 |
| 评测 | 20 条 case × 9 层（a1/a2/a3/b2/b3/b4/metric/honesty/security）+ 门禁 | ⚠️ 门禁从未真跑绿 |
| 安全 | PolicyGuard deny-by-default / 红队 3/3 / SBOM / 密钥零泄漏 / 受控工具 | ✅ |
| UI | 薄壳（SSE 实时/证据链/取消）| ✅ M8-4 |

## 3. 数据现状（真实基础设施）

| 数据 | 状态 |
|------|------|
| MySQL 订单分片（16 节点）| **t_order 0 行**（演示数据，无业务流量）|
| t_payment | **0 行** |
| t_cart_event | **316 行**（有真实事件数据）|
| t_note_event | 0 行 |
| my_xhs_ai（AI 自有库）| ai_run **28 行** / ai_step **246 行**（真实运行记录）|
| 日志快照 | 22 个中间件日志（生产容器审查快照 20260811）|
| Prometheus/ES/SkyWalking | 可用（远端）|

> **核心事实：系统真实、数据演示级。** 链路全部真实可用，但业务数据基本为 0——Agent 的调查结果基于近乎空的库（8 单/46 单是历史测试数据）。

## 4. 架构现状（重构后）

```
前端薄壳 → POST /api/runs(convId 规划中)
  → 三阶意图路由（L0 规则/L1 LLM/L2 语义/L3 默认）
  → RunManager（事件缓冲/单消费者/cancelStream/崩溃恢复）
  → AgentHarness（executeLoop 调度 + handleToolCall/Decline/Answer）
      → PolicyGuard（allowlist + 参数白名单 + L3 恒拒绝）
      → MCP 14 工具（真实数据源）
      → 存在性校验（registry）+ DECLINE 零证据豁免
  → SSE 事件流（HarnessEventType 枚举契约）
横切：Run Store（checkpoint/心跳/finalAnswer 落库）、RunMetrics、traceId（仅同步线程）、MCP 认证+审计
```

**已完成的重构**：事件枚举化 / executeLoop 拆分（139→70 行 + 3 handler）/ 工具输出类型化（ToolJson + record）/ StepState 枚举 / 路由主次反转（LLM 负责理解）/ 深度 review 全部修复。

## 5. 质量现状

- **192 测试全绿**（单元为主；集成测试 6 个凭据跳过）
- **eval-gate 门禁 7/7 失败**（2026-08-14 一次真跑，passRate 0.0，未追查——**最大质量债**）
- 架构 review：P0 已修（路由双入口不一致）、P1×3/P2×3 已修，报告归档
- 已知问题：模型发散（BUDGET_STEPS 兜底）、traceId 不覆盖异步线程、审计为应用日志（非独立流）

## 6. 技术债与欠账（规划输入）

| 类别 | 欠账 |
|------|------|
| **功能欠账**（四态分离只做 2/4）| Conversation（会话）❌ Memory（记忆）❌ |
| **D5 欠账** | HITL 审批流 ❌（L3 恒拒绝）|
| **M9 欠账** | 受控执行类工具（仅只读 log.search）|
| **D6 欠账** | 评测 20 条（目标 300+）/ LLM-as-judge ❌ / bad case 回流 ❌ / Langfuse ❌ |
| **架构欠账** | 工具注册表 ❌（3 个硬编码接口）/ 多智能体未决 / 模型分层冻结 |
| **质量债** | eval-gate 未跑绿 / 阈值拍脑袋（10/60/40）|
| **工程债** | 部署未验证（docker 无）/ CI 未真实运行 / gateway 未接入 |

## 7. 外部依赖状态

gateway 路由（方案已交付未实施）/ 慢查询管道（需求单已发）/ 业务流量（无）/ MCP_API_KEY 生产（未设）/ 部署环境（未定）。

---

> 结论：**技术栈与工程质量达到"可演示的 Agent 平台"水平；功能完整性约 40%（会话/记忆/HITL/多 Agent/评测闭环缺失）；数据真实性约 30%（业务表空）。** 后续规划（roadmap-m10-m14）以此为基线。
