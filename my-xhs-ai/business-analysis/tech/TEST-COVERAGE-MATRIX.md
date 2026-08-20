# my-xhs-ai 测试覆盖矩阵（2026-08-19）

> 目标：明确区分哪些已经测过，哪些只是 PoC，哪些是真实 E2E，避免把所有测试混成一句“系统性测试过了”。

---

## 一、总体结论

当前项目已经做了**分层测试**，但还没有达到“全量系统性验证”的程度。

可以准确表述为：

- **单元 / 组件 / 控制器 / 契约层测试：已较完整**
- **关键能力已有真实 E2E 证据**
- **PoC 级能力（如 Temporal）已有实验验证**
- **大规模 release 级系统性验证（300+ case / 红队 / 压测 / 回滚）仍未完成**

---

## 二、测试分层矩阵

| 层级 | 已覆盖 | 当前状态 |
|------|--------|----------|
| 单元测试 | Harness / Router / Eval / Store / Conversation / Tools | ✅ 较完整 |
| 控制器测试 | RunController / AiQueryController / StreamController | ✅ 已覆盖 |
| MCP 契约测试 | MCP auth / tools contract | ✅ 已覆盖 |
| 真库真模型评测 | smoke / regression / sampled / e2e | ✅ 已有，但规模偏小 |
| 真实外部 E2E | DLQ / Langfuse / demo 脚本 | ✅ 已有关键证据 |
| Temporal PoC | 审批 workflow + restart demo | ✅ PoC 级已验证 |
| 红队/安全体系 | Prompt Injection / 越权 / PII 全量体系化 | ⚠️ 部分（非系统化） |
| 压测/回滚/部署演练 | Docker / K8s / canary / rollback | ❌ 未完成 |

---

## 三、已覆盖测试清单

### 3.1 App 层测试（`my-xhs-ai-app/src/test/java`）

#### 配置 / Controller
- `EvalConfigTest`
- `AgentRunStreamControllerTest`
- `AiQueryControllerTest`
- `AiQueryMetricPathTest`
- `RunControllerTest`
- `RunMetricsTest`

#### Eval / 评测体系
- `E2EEvalRunnerTest`
- `E2EEvalTest`
- `EvalAsserterTest`
- `EvalFrameworkTest`
- `EvalGateRunTest`
- `EvalGateTest`
- `EvalM14Test`
- `EvalRegressionFullRunTest`
- `EvalSmokeFullRunTest`
- `EvalSmokeRunTest`
- `MultiAgentComparisonTest`
- `SampledRegressionTest`

#### Harness / Agent 核心
- `AgentHarnessTest`
- `HarnessCoreTest`
- `HitlApprovalTest`

#### Profile / Router / Conversation / RunStore
- `AgentDispatcherTest`
- `ConversationServiceTest`
- `MetricRealDbIntegrationTest`
- `QueryWindowExtractorTest`
- `IntentRouterTest`
- `SemanticIntentClassifierTest`
- `SemanticRouterIntegrationTest`
- `RunManagerTest`
- `JdbcRunStoreTest`

#### RAG / Labs
- `MetricDictionaryIngesterTest`
- `RrfFusionTest`
- `TemporalApprovalWorkflowTest`
- `TemporalApprovalRestartTest`

### 3.2 Tools 层测试（`my-xhs-ai-tools/src/test/java`）
- `BaselineWindowToolTest`
- `ContentInteractionToolTest`
- `DirectLogSearchAccessTest`
- `DlqRedeliverToolTest`
- `EventAnalyticsToolTest`
- `MetricWindowTest`
- `OrderMetricsToolTest`
- `PaymentMetricsToolTest`
- `PrometheusQueryToolTest`
- `ToolRegistryTest`

### 3.3 MCP 层测试（`my-xhs-ai-mcp/src/test/java`）
- `McpAuthTest`
- `McpContractTest`

---

## 四、哪些属于“真 E2E”

### 4.1 已跑通的真实外部 E2E

#### A. DLQ 死信诊断 + 重投
- Agent → `mqDlqQuery`
- 提取 `ORIGIN_MESSAGE_ID`
- HITL 审批
- `dlq.redeliver`
- **`CR_SUCCESS`**

#### B. Langfuse Trace
- Agent run trace 已真实上报到 Langfuse Cloud
- 已展示：`userId / sessionId / model / tokens / cost / tool output`

#### C. Demo 脚本
- `demo-dlq.sh`
- `demo-order-decline.sh`
- `demo-5xx.sh`
- `demo-temporal-restart.sh`

#### D. Temporal PoC
- 本机 Temporal dev server
- 独立 worker 进程
- `WAITING_APPROVAL`
- kill worker
- restart worker
- approve
- `COMPLETED`

### 4.2 真实 E2E 评测
报告：`my-xhs-ai/docs/reports/e2e-eval-report-20260819.md`

结果：
- case 数：5
- 完成率：100%
- 通过率：80%
- 幻觉误报：1（评测器误把错误消息中的数字当成幻觉）

### 4.3 系统知识问答评测
- `KnowledgeEvalRunnerTest`
- 真实结果：**9 / 9 通过**
- 覆盖 architecture / business / code structure 三类问题
- 说明 cards/maps 已经不只是静态资产，而是问答主路径中的可运行能力

---

## 五、哪些只是 PoC / 不能夸大

### 5.1 Temporal
当前只能说：
- **PoC 已验证 durable execution 场景**
- 不能说：主线已经全面迁移到 Temporal

### 5.2 多 Agent
当前只能说：
- **BUSINESS / OPS / FULL profile 分流已做**
- 不能说：真正多 Agent 协作系统已完成

### 5.3 Memory
当前只能说：
- **基础长期记忆已实现（豆包 embedding + 语义检索 + 多用户隔离）**
- 不能说：MemoryOS 级三层记忆管理已完成

### 5.4 Langfuse
当前只能说：
- **Langfuse trace 已接通，可展示 run/generation/tool/answer**
- 不能说：生产级可观测平台全套落地已完成

---

## 六、未覆盖 / 尚不足之处

### 6.1 规模不足
- E2E case 目前只有 5 条，不是 50/300 条
- release 级评测门禁未形成

### 6.2 安全体系不足
- 没有形成完整的红队测试集
- Prompt Injection / 越权 / PII 仍以点状验证为主

### 6.3 部署体系不足
- 没有系统做 Docker / K8s / 回滚 / canary 演练
- 没有容量/压测报告

### 6.4 可观测体系不足
- Langfuse 已接通，但没有把 trace 与评测报告、Dashboard、告警打通

---

## 七、测试视角下的收官结论

### 可以说的
- 已做**分层测试**（单元 / 控制器 / 契约 / 真模型评测 / 真实外部 E2E / PoC）
- 关键链路已有**真实证据**，不是只有 mock
- 项目已经达到**可展示、可面试、可交接**的程度

### 不能说的
- 不能说“已经做了完整系统性测试”
- 不能说“生产级验证已经全面完成”
- 不能说“300+ 评测、红队、压测、回滚都做完了”

### 我的判断
从测试视角看：

> **这个项目已经可以收官，但属于“有关键真实证据支撑的收官”，不是“全量生产级验证完毕的收官”。**

这是一个诚实且足够强的口径。
