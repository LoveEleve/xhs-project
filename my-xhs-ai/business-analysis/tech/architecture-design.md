# Detailed Architecture Design（DAD）

> 版本：v0.1 | 日期：2026-08-10
> 定位：把 PLAN v6 §3 骨架**深化为可落地设计**（组件分解 / 时序流 / 数据模型 / 接口契约 / 韧性接线 / 待定问题）。
> 读者：先懂名词看 `../02-plan/concepts-glossary.md`；总规划看 `../02-plan/PLAN-v6.md`。
> 原则：**UI 是薄壳、API/SSE 是产品、安全内建在工具层**（ADR-005）。

---

## 1. 组件分解（模块级，标注归属服务与关键类）

```
                    ┌────────────────────────────────────────────┐
                    │  my-xhs-gateway 19080（现有，认证/角色/限流）│
                    └──────────────┬─────────────────────────────┘
                                   │ 认证后 userId+roles
        ┌──────────────────────────▼──────────────────────────┐
        │                my-xhs-ai  19020（自研核心）            │
        │                                                       │
        │  IntentRouter ──路由：指标/观测/RAG/Workflow/Agent     │
        │    ├─ DeterministicPath ─ 固定查询（工具直取，不走Agent）│
        │    ├─ RAGService ── 知识库问答                         │
        │    └─ AgentPath ── 开放调查（业务/排障）                │
        │                                                       │
        │  AgentHarness（核心循环）                               │
        │    ├─ LoopCtrl     循环/预算/重复检测/终止              │
        │    ├─ StepEngine   plan/act/observe 执行步骤            │
        │    ├─ EvidenceChain 证据/反证/来源收集                  │
        │    ├─ HitlGate      L3 高危动作→暂停等人工              │
        │    └─ PolicyGuard   工具白名单/角色/字段/参数校验        │
        │                                                       │
        │  RunManager ─ Run/Step 状态机 + Checkpoint（D5）        │
        │  ConversationService ─ 会话/摘要/长期记忆               │
        │  ModelProvider ─ LangChain4j 封装（火山方舟）           │
        │  Guardrails ─ 注入检测/PII脱敏/内容过滤（横切）          │
        │  AuditService / TraceService(OTel) / CostTracker        │
        └──────┬───────────────┬────────────────┬────────────────┘
               │ MCP client     │ SSE            │ REST
        ┌──────▼──────┐  ┌──────▼──────────┐  ┌──▼─────────────┐
        │ my-xhs-ai-mcp│  │ my-xhs-ai-ui     │  │ 其他系统/研发    │
        │ 19021 工具层 │  │ 19022 薄Web壳     │  │ (API 调用者)    │
        └──────┬──────┘  └─────────────────┘  └────────────────┘
               │ MCP server（固定只读工具）
   ┌───────────┼───────────────┬───────────────┐
   │ business-mcp (L1)         │ observability-mcp (L2)
   │ order/payment/inventory/  │ mysql(慢查询/死锁) redis mq
   │ coupon 指标语义层          │ promql es-log trace dlq
   └───────────────────────────┴───────────────────────
```

**模块 ↔ 责任 ↔ 关键类（D1 起逐步实现）**
| 模块 | 责任 | 关键类（草案） |
|------|------|---------------|
| IntentRouter | 判断请求走 指标/观测/RAG/Agent | `IntentRouter`（+分类器） |
| DeterministicPath | 固定查询直接调工具、带来源返回 | `MetricQueryService` |
| AgentHarness | ReAct 循环 + 预算 + 循环检测 + HITL + 证据链 | `AgentHarness`, `LoopCtrl`, `StepEngine`, `HitlGate` |
| PolicyGuard | deny-by-default 工具/角色/参数白名单 | `PolicyGuard`, `ToolPolicy` |
| RunManager | Run/Step 状态机 + checkpoint | `RunManager`, `RunStateMachine` |
| ModelProvider | 统一模型接入/结构化输出/工具调用 | `ModelProvider`（LangChain4j） |
| Guardrails | 注入/PII/内容过滤（横切） | `InjectionGuard`, `PiiRedactor`, `ContentFilter` |
| Audit | 不可变审计流 | `AuditService` |
| Trace | OTel + Langfuse | `TraceService` |
| MCP 工具层 | 暴露固定只读工具（L1/L2） | `BusinessTools`, `ObservabilityTools` |

---

## 2. 关键时序流（3 条核心路径）

### 2.1 固定指标查询（Deterministic，不走 Agent）
```
运营 "今天订单量？"
  → gateway 认证(role=ops)
  → IntentRouter: 固定指标 → DeterministicPath
  → 校验 PolicyGuard(工具白名单+L1)
  → MCP 调用 business.order_query_volume
  → 返回{数字, 口径, 时间窗, 数据时间, 来源}
  → Guardrails(PII) → 组装答案 → SSE run.completed
```
> 全程无 LLM 决策，数字来自确定性工具 → 可重复、可逐字段校验。

### 2.2 Agent 调查（业务/排障归因，核心）
```
"为什么订单量下降？"
  → IntentRouter → AgentPath（进入 Harness）
  → Harness 创建 Run + Step1
  循环:
    think:  模型决定下一步（查什么/用什么工具/为什么）
    tool:   PolicyGuard校验 → MCP工具 → 结果回填
    observe: 模型评估，追加证据链 / 反证
    loopcheck: 连续同工具/同状态? 预算(步骤/Token/成本)达标? → 停
    hitl:   若工具属 L3（重启/重投）→ 暂停 wait_approval
  终止条件: 已归因 OR 预算尽 OR 用户取消 OR 需人工
  → 组装答案：结论 + 证据链 + 反证 + 不确定性声明
  → Run.completed/failed/partial + SSE 全程事件
```

### 2.3 RAG 知识问答
```
"退款口径是什么？"
  → IntentRouter → RAGService
  → 检索（混合检索 + 权限过滤 ACL）
  → 带引用回原文 → Guardrails → 答案
  → 无可信结果 → 拒答/声明不确定
```

---

## 3. 数据与状态模型（四类状态分离，PLAN §3.4）

### 3.1 Run（一次任务）
```
Run { runId, type(BUSINESS_DIAGNOSIS/OBSERVATION_DIAGNOSIS/RAG/METRIC),
      status(RECEIVED→AUTHORIZED→PLANNED→RUNNING→SUCCEEDED/PARTIAL/FAILED/CANCELLED/EXPIRED),
      budget{maxSteps, maxTokens, maxCost}, used{steps,tokens,cost},
      userId, roles, createdAt, startedAt, endedAt, summary }
```

### 3.2 Step（一步执行，可 checkpoint）
```
Step { stepId, runId, seq,
       type(THINK/TOOL/RAG/ANSWER),
       toolName?, params?, result?, 
       status(PENDING/RUNNING/SUCCEEDED/FAILED/WAITING_APPROVAL),
       tokens, durationMs, evidenceRefs[] }
```

### 3.3 Conversation（会话上下文，与 Run 分离）
```
Conversation { convId, messages[](role,content,refs), summary,
               longTermMemory? }
```

### 3.4 Trace（可观测，与 Run 打通）
```
traceId = runId；span = step（model/tool/retrieval 调用，含延迟/token/错误状态）
```

### 3.5 关键不变量
- 业务事实 = 原服务权威存储；Run/Step/Conversation/Memory 四类**严格分开**。
- Step 是 checkpoint 粒度：Worker kill 后按 Step replay 恢复（§6.1 #8）。

---

## 4. 接口契约

### 4.1 REST API（my-xhs-ai）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/runs` | 发起任务（body: type, query, convId?）→ 返回 runId |
| GET | `/api/runs/{id}` | 查 Run/Steps 摘要 |
| GET | `/api/runs/{id}/events` | SSE 事件流 |
| POST | `/api/runs/{id}/cancel` | 取消 |
| POST | `/api/runs/{id}/approve` | HITL 人工审批（传 decision+reason）|
| GET | `/api/tools` | 当前用户可见工具清单（权限过滤）|
| GET | `/api/monitor` | 健康/指标 |

### 4.2 SSE 事件类型
```
run.started / run.waiting_approval / run.completed / run.failed / run.cancelled
step.thinking / step.tool_start / step.tool_result / step.rag / step.answer_partial
error   // 含错误分类（timeout/retryable/non_retryable/policy_denied）
```
> 事件含 `runId` + `stepId` + 时间戳，供 UI 差分渲染（参考 pi TUI 思路）。

### 4.3 MCP 工具契约（my-xhs-ai-mcp）
每个工具固定 schema（输入/输出/权限级别/截止时间/分页/窗口上限）：
```
business.order_query_volume  (L1)  in:{dim, timeWindow}  out:{value, 口径, 数据时间, 来源}
observability.mysql_slow_query (L2) in:{timeRange, limit} out:{[]慢查询, 来源}
```
- 全部只读；无任意 SQL/PromQL/ES DSL 入口。
- 工具输出需可被非 LLM 测试逐字段验证（契约测试）。

---

## 5. 韧性接线（对接 §6.1 清单）

| §6.1 # | 失败模式 | 落在哪个模块/机制 |
|:--:|---------|------------------|
| 1 | 死循环 | LoopCtrl（连续同工具/同状态上限）+ 预算 |
| 2 | 超预算 | LoopCtrl budget（步骤/Token/成本三重封顶）|
| 3 | 模型超时/不可用 | ModelProvider 超时 + 降级 |
| 4 | 工具超时 | 工具 deadline（MCP 层）|
| 5 | 重试/退避 | RunManager 重试策略 |
| 6 | 取消 | RunManager cancel |
| 7 | 重复消息/幂等 | RunManager 幂等键 |
| 8 | Worker 崩溃 | RunManager checkpoint + replay（Step 粒度）|
| 9 | 部分成功 | Run PARTIAL + 明确未完成 |
| 10 | 高危动作 | HitlGate（L3）|
| 11 | 成本失控 | CostTracker + 预算 |
| 12 | 不确定结论 | EvidenceChain 输出反证 + 不确定性 |
| 13 | 中间件不可用 | 降级 + 故障演练 |
| 14 | 越权/注入/PII | PolicyGuard + Guardrails + Audit |

---

## 6. 待定问题 / 风险清单（D0/D1 需拍板）

| # | 问题 | 现状/倾向 | 需拍板阶段 |
|:--:|------|----------|:--:|
| 1 | 模型：日常查询 vs 排障归因是否分模型 | 倾向：简单固定用普通模型，归因用推理模型 | D1/D4 |
| 2 | ES knn vs 独立向量库(Milvus) | 倾向：先 ES，质量/容量不足再引 | D3 |
| 3 | HITL 审批的传输/界面 | SSE 事件 + UI 按钮 | D2/D5 |
| 4 | 评测阈值（幻觉率/时延/成本） | 先测分布再定 | D6 |
| 5 | Langfuse 自托管 vs 云 | 自托管（避免数据外传）| D6 |
| 6 | 长期记忆何时引入 | 仅当会话摘要不够再上 | D5 |
| 7 | 数据缺口依赖（A1/A5/B1/B2） | 依赖对应服务 Owner 补齐（D0 门禁）| D0 |

---

## 7. 与 PLAN 的关系
- 本 DAD 是 PLAN §3 的深化，不新增范围；所有机制已映射到 §6.1 / §7 评测 / SLO。
- 后续 D1-D7 按本 DAD 的模块边界逐步实现，类名/契约以实际代码为准（本文件为草案起点）。
