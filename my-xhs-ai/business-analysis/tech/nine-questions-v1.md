# 九问面试讲稿（my-xhs-ai）

> 日期：2026-08-17 | 定位：面试深度答法 | 配套：`pitch-3tier-v1.md`（三档总述）、`retrospective-v1.md`（总复盘）、`jd-hit-matrix-v1.md`（JD 命中）
> 用法：每题先给"一句话回答"，对方追问再展开。证据统一指向可当场打开的代码/测试/归档报告。

---

## 项目状态声明（开场用，1 分钟）

这个项目当前处于"研发完结、工程收尾"阶段：

- 核心能力（受限 Agent、工具注册表、HITL、会话、多智能体、评测）已实现并有测试
- 本地真模型真工具链的 `EvalGateRunTest`、`SampledRegressionTest` 已跑绿（归档 `docs/reports/eval-gate-report-20260817`）
- 已识别的边界有三个，且都有明确下一步：
  1. 远端 CI 未真跑 → 下一步接入并验证凭据/MCP/超时
  2. nightly 100 条未首跑 → 下一步跑全量并产出成本/时延报告
  3. `dlq.redeliver` 真实端到端受上游接口 NPE 阻塞 → 已定位到 `ORIGIN_MESSAGE_ID` 查询接口，等上游修复或换 `mqadmin` 查询

先把边界摆在明面上，后面每题就不用再遮遮掩掩。

---

## Q1. 为什么固定查询不用 Agent，归因才用 Agent？

**一句话**：确定性查询是 Workflow，归因才是 Agent 的动态调查能力。

**展开**：固定查询有明确输入、口径和 SQL，模型参与只增加成本和随机性；"为什么下降"需要跨指标、跨窗口、跨数据源组合，才交给受限 Agent。`IntentRouter` 规则强信号优先，复杂归因才进 Harness。

**证据**：`IntentRouter`、`RunManager`、`IntentRouterTest`。  
**深度证据**：架构 review 曾发现 `RunManager` 与 `AiQueryController` 注入的 `IntentRouter` 实例不一致（一个纯规则、一个含 LLM 分类），导致同系统两入口行为分裂，是 review 抓出来并修掉的真实 P0（出处 `architecture-review-v1.md`）。

---

## Q2. 如何降低模型编造业务数字的风险？

**一句话**：运行时靠证据引用存在性校验，评测层再用数字一致性检查识别明显异常。

**展开**：每次真实工具调用登记 evidenceId，最终答案的 `evidenceRefs` 必须命中本轮记录，否则拒绝并重想。数字一致性检查作为启发式质量信号，不替代业务语义验证。

**证据**：`AgentHarness.validateAnswer`、`ToolResultRegistry`、`EvalAsserter.checkNumberConsistency`。

---

## Q3. Tool、MCP、Workflow、HITL、A2A 分别解决什么？

**一句话**：它们分属能力、接入、编排、治理、协作五层。

| 层级 | 概念 | 作用 |
|---|---|---|
| 能力层 | Tool | 定义系统允许执行什么 |
| 接入层 | MCP | 标准化工具发现、描述和调用 |
| 编排层 | Workflow/Agent | 决定路径由代码还是模型控制 |
| 治理层 | HITL | 高风险动作由人授权 |
| 协作层 | A2A | 独立 Agent 间通信 |

**证据**：`ToolRegistry`、MCP 工具目录、`WAITING_APPROVAL`、BUSINESS/OPS profiles。  
**主动边界**：A2A 未引入是刻意克制，多个 Agent 同进程共享 Harness，暂无跨系统通信需求。

---

## Q4. 为什么 RocketMQ、Redis 不等于 Durable Agent Runtime？

**一句话**：消息系统只传递任务，Durable 还要保存执行状态、恢复位置和幂等语义。

**展开**：Agent 一次执行含多轮决策、工具结果、证据和 checkpoint。崩溃后仅靠 MQ 无法判断已执行到哪步。需要 Run/Step Store、状态 CAS、checkpoint、心跳和 resume。

**证据**：`JdbcRunStore`、`RunManager`、checkpoint、`claimRunning`。  
**主动边界**：这是单服务 Durable 子集，不宣称等同 Temporal 通用工作流平台。

---

## Q5. Run、Conversation、RAG、Memory 为什么分开？

**一句话**：四者生命周期、所有权、更新语义不同，混在一起会污染当前结论。

- Run：执行状态、步骤、证据
- Conversation：多轮消息关系
- RAG：外部知识与口径
- Memory：跨会话长期事实/偏好

**当前实现**：Run、Conversation、RAG 已实现；会话内是**规则抽取结论段**的摘要（非 LLM 摘要记忆）。  
**主动边界**：独立长期 Memory Store、案例记忆、偏好记忆未实现，是明确的下一步。

**证据**：`ai_run/ai_step`、`ai_conversation/ai_message`、ES RAG、`ConversationService` 规则摘要。

---

## Q6. 非确定性 Agent 如何做回归门禁？

**一句话**：把质量拆成硬断言、数字一致性、统计阈值和可选 Judge，不比完整文本。

**展开**：硬断言查状态/证据数/关键词，数字一致性检测无证据数字，套件统计通过率/完成率/幻觉率/时延/成本，Judge 补开放式判断，bad case 回流。

**证据**：`EvalRunner`、`EvalAsserter`、`EvalGate`、`EvalJudge`、`BadCaseCollector`；`EvalGateRunTest` 7/7、`SampledRegressionTest` 通过（归档 `docs/reports/eval-gate-report-20260817`）。

---

## Q7. Prompt Injection 无法完全识别，如何防止越权？

**一句话**：不靠模型识别攻击，而是让攻击即使影响模型也突破不了确定性权限边界。

**能防**：未注册工具、越权、任意 SQL/Shell/PromQL、未审批执行 L3。  
**不能单独防**：提示词泄露、RAG 恶意内容、敏感数据复述、全部间接注入。

**证据**：`PolicyGuard`、ToolRegistry access level、参数白名单、MCP 认证、HITL。  
**主动边界**：Prompt Leakage / PII 脱敏 / 红队 ASR 是安全深化的下一步。

---

## Q8. 工具调用后崩溃，如何避免丢进度或重复副作用？

**一句话**：用 checkpoint、状态认领、执行记录和工具幂等边界恢复，不盲目重跑。

**展开**：步骤保存消息快照和证据，恢复时原子认领超时 Run，从 checkpoint 重建上下文；只读工具天然幂等，执行型工具靠审批记录和防重状态。

**证据**：`JdbcRunStore`、`RunManager`、HITL resume、approval JSON。

---

## Q9. 如何权衡质量、时延、成本和安全？

**一句话**：安全优先，质量由门禁约束，时延和成本在不破坏前两者下优化。

**机制**：固定查询走确定性工具、单一 Flash 模型、步骤/Token/成本三重预算、工具结果长度限制、异常重试和降级、SSE 反馈。

**证据**：`AgentBudget`、循环检测、RunMetrics、评测报告。  
**主动边界**：P50/P95、容量、月度成本结论等 nightly 全量报告，不宣称已完成成本优化。

---

## 高频追问（与九问同级，不是附录）

### 为什么用 Java/LangChain4j，而不是 Python/LangGraph？

**一句话**：因为目标系统要接入现有 Java/Spring Cloud 电商平台，Java 能直接复用服务、权限、数据库和监控体系。

**展开**：LangChain4j 已覆盖模型调用、结构化输出和工具调用；Python/LangGraph 生态更丰富，但迁移和业务接入成本更高。这是"业务约束优先于框架流行度"的取舍。

**证据**：项目本身就是 Spring Boot + LangChain4j + MCP，工具桥接复用现有微服务数据源。  
**主动边界**：可以补一个 Python/LangGraph 对照 PoC 作为加分项，但非当前主线。

### 为什么自研 Harness，而不是直接用成熟 Agent 框架？

**一句话**：核心难点不是"让模型调工具"，而是精确控制 checkpoint、权限、证据、HITL、恢复和持久化。

**展开**：成熟框架提供基础编排能力，但没有直接覆盖这套 Java 业务约束；自研范围被限制在"执行状态机"，不是重新实现整个 Agent 生态。

**证据**：`AgentHarness` 的状态机、存在性校验、预算、循环检测、HITL 都是围绕"可审计、可恢复、可评测"设计，且都有测试。  
**主动边界**：这属于"自研 vs 现成"的克制取舍，能讲清"为什么这里必须自研"比"我用了 5 个框架"更值钱。

---

## 使用说明

- 每题先一句话，追问再展开，避免"背稿"感。
- 边界统一在"项目状态声明"集中声明，九问内点到即止。
- 证据引用统一指向 `docs/reports/` 与 `review-m*-impl.md`，可当场打开。
