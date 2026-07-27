# my-xhs-ai 工业级 Agent 学习与交付规划 v5

> 版本：v5.0
> 核验日期：2026-07-25
> 定位：业务项目交付主线 + Agent 工程学习主线 + 前沿研究实验支线
> 历史版本：[v4 规划](reference/PLAN-v4-legacy.md)

## 1. 规划目标

本规划同时服务两个目标，但不再把它们混成一条串行路线。

### 1.1 产品目标

交付一个与 my-xhs 真实业务和基础设施深度结合的工业级 Agent 项目：

> **my-xhs 运营异常诊断 Agent**：面向运营和技术支持人员，对订单、支付、库存、内容、搜索及系统指标的异常进行受限、可恢复、可审计的多步调查，并输出带证据的原因线索和行动建议。

它不是万能聊天机器人，也不是让 LLM 直接查询所有数据库的自然语言 BI。

### 1.2 学习目标

完成后应具备以下能力：

1. 理解 Transformer、LLM、Prompt、结构化输出和 Tool Calling 原理。
2. 能设计 RAG、上下文、记忆和长期任务状态，而不是只会调用框架 API。
3. 能区分指标查询、RAG、确定性 Workflow 和 Agent 的适用边界。
4. 能用 Java 构建 MCP Tool、受限 Agent、持久工作流和流式 API。
5. 能建立评测、安全、可观测、成本、发布和回滚体系。
6. 能读论文、复现实验、做技术选型，并用数据解释取舍。
7. 面试时能完整讲清一个生产 Agent 从业务问题到上线治理的全过程。

### 1.3 成功标准

项目完成不以“实现了多少框架和概念”为标准，而以以下证据为标准：

- 至少 3 个真实异常诊断场景端到端运行。
- 所有业务数字由确定性工具产生，并带口径、时间窗、数据时间和来源。
- Agent 只能调用经过授权的固定工具，不能生成任意 SQL、PromQL 或 ES DSL。
- 长任务支持超时、取消、重试、恢复、部分结果和人工介入。
- 每次运行可追溯模型、Prompt、工具、检索、策略和评测版本。
- 具备版本化评测集、PR 回归、故障注入、红队测试和发布门禁。
- 具备 trace、指标、成本、审计、告警、灰度和回滚能力。
- 产出可公开讲解的架构、ADR、评测报告、故障报告和演示材料。

---

## 2. 先确定什么需要 Agent

判断标准：**如果下一步动作需要根据上一步观察结果动态决定，才优先考虑 Agent。**

| 业务请求 | 默认实现 | 原因 |
|---|---|---|
| “今天订单量多少？” | 指标 API / 固定 SQL | 路径确定，要求数字可重复 |
| “支付成功率按渠道分布？” | 指标 API | 固定聚合，不需要自主推理 |
| “生成每周运营报表” | Durable Workflow | 流程固定，适合调度和模板 |
| “退款口径是什么？” | RAG | 答案来自业务文档和指标字典 |
| “为什么今天订单下降？” | 受限 Agent | 调查路径依赖中间证据 |
| “执行退款、发券、改库存” | 暂不支持 | V1 只读，写操作风险过高 |

### 2.1 V1 核心场景

#### 场景 A：订单下降诊断

1. 确认调查时间窗和对比基线。
2. 查询订单量、状态和来源分布。
3. 根据结果选择支付、库存、商品或系统异常工具。
4. 获取支持证据和反证。
5. 输出按置信度排序的原因线索，不宣称未经验证的因果关系。

#### 场景 B：支付成功率下降诊断

1. 查询支付成功率和渠道分布。
2. 对比历史基线并定位异常时间段。
3. 查询支付服务指标、错误日志和订单状态。
4. 输出影响范围、证据、未知项和下一步建议。

当前支付事实模型缺少失败原因字段。D0 阶段必须补充失败码或支付失败事件，否则 Agent 只能回答“哪个渠道下降”，不能回答“为什么失败”。

#### 场景 C：库存预扣失败诊断

1. 查询库存预扣失败率和异常 SKU。
2. 区分库存不足、并发冲突、TCC 异常和服务错误。
3. 关联订单流量、库存水位和 Prometheus 指标。
4. 输出受影响 SKU、失败类型和处理建议。

### 2.2 后续场景

- 内容发布和审核异常诊断。
- 搜索无结果率和 Feed CTR 异常诊断。
- 用户活跃度下降诊断。
- 异步运营周报生成。
- 告警触发的自动调查和人工确认。

这些场景必须在对应事实数据和指标口径准备完成后进入开发。

#### 已知数据准备清单

- 支付记录补充稳定失败码、失败阶段和支付渠道响应摘要。
- 退款记录补充商品级明细，否则不能准确计算商品退款率。
- 笔记补充 `published_at` 和 `audited_at`，区分创建、发布和审核时间。
- 关注和取消关注改为事件流水或历史表，支持净粉丝增长分析。
- 搜索补充查询、结果数、曝光和点击事实，统一行为类型枚举。
- 订单、支付、库存和内容建立版本化日/小时汇总投影，避免跨分片在线扫描。

### 2.3 明确不做

- 不让 LLM 直连数据库或生成任意查询语言。
- 不默认拆成 10 个 MCP 微服务。
- 不把普通并行工具调用包装成多 Agent。
- 不在 V1 开放生产写操作。
- 不把聊天记录当成业务事实源。
- 不因框架支持某项能力就自动引入该能力。
- 不用 star 数、营销文案或单篇论文直接替代 ADR 和实测。

---

## 3. 系统架构

### 3.1 逻辑架构

```text
运营人员 / Prometheus 告警
             |
             v
my-xhs-gateway
认证、运营角色、限流、request context
             |
             v
my-xhs-ai
├── Intent Router
│   ├── 指标查询 ----------> Deterministic Query
│   ├── 业务知识问答 ------> RAG
│   ├── 固定报告 ----------> Durable Workflow
│   └── 开放异常调查 ------> Bounded Agent
├── Agent Harness
│   ├── plan / act / observe
│   ├── step、token、cost、deadline budget
│   ├── loop detection / partial result / HITL
│   └── evidence / counter-evidence / uncertainty
├── Run API + SSE
└── Run State / Conversation State
             |
             v
Tool Gateway / MCP Client
             |
             +--> analytics-mcp
             |    固定指标、只读领域 API、分析投影
             |
             +--> observability-mcp
             |    Prometheus、ES 日志、trace、DLQ
             |
             +--> knowledge-rag
                  指标字典、Runbook、Schema、业务文档

横切能力：Policy、Eval、OpenTelemetry、Langfuse、Audit、Cost、Model Gateway
```

### 3.2 部署边界

V1 只部署两个新增服务：

| 服务 | 计划端口 | 职责 |
|---|---:|---|
| `my-xhs-ai` | 19020 | API、路由、Agent、RAG、运行状态、SSE |
| `my-xhs-ai-mcp` | 19021 | 受控业务指标和只读查询工具 |

出现以下证据之一后才拆分 MCP 服务：

- 不同数据安全或凭据边界。
- 不同团队所有权和发布节奏。
- 独立扩缩容需求。
- 故障隔离要求。
- 合规或网络隔离要求。

可观测工具需要独立凭据时，再拆出 `observability-mcp`，不为每个数据库表创建一个服务。

#### 建议 Maven 结构

```text
my-xhs-ai/
├── pom.xml                    # 聚合模块
├── my-xhs-ai-app/             # 可部署：API、Router、Agent、RAG、Run
├── my-xhs-ai-mcp/             # 可部署：受控只读工具
├── my-xhs-ai-contracts/       # Tool schema、事件和共享契约
├── evals/                     # 数据集、Promptfoo、离线报告
├── labs/                      # AgentScope、MemoryOS、Temporal、vLLM 实验
└── docs/                      # 产品、课程、论文、ADR 和运行手册
```

只有 `app` 和 `mcp` 是 V1 部署单元；`contracts` 是普通 Java 库，`evals` 和 `labs` 不进入生产镜像。

### 3.3 数据边界

工具数据来源优先级：

1. 领域只读 API。
2. 指标语义层或运营汇总表。
3. CDC 形成的分析投影或只读副本。
4. 经过审核的固定查询。
5. 禁止 LLM 面向任意业务表自由查询。

每个工具结果必须包含：

```json
{
  "status": "ok",
  "metric": "payment_success_rate",
  "definitionVersion": "payment-success-rate/v1",
  "window": {"from": "...", "to": "...", "zone": "Asia/Shanghai"},
  "asOf": "...",
  "value": 0.973,
  "dimensions": {},
  "source": "payment_daily_summary",
  "quality": {"complete": true, "warnings": []},
  "traceId": "..."
}
```

### 3.4 四类状态必须分开

| 状态 | 示例 | 权威存储 |
|---|---|---|
| 业务事实 | 订单、支付、库存 | 原业务服务和数据库 |
| Durable Run State | 当前步骤、attempt、审批、幂等键 | Workflow History / Run Store |
| Conversation State | 当前会话、摘要、上下文预算 | Redis + 持久会话表 |
| Long-term Agent Memory | 用户偏好、历史经验 | 独立 Memory Store，按需引入 |

MemoryOS、Mem0 或 Letta 不能成为订单和支付事实源，也不能替代 durable workflow state。

### 3.5 运行状态机

```text
RECEIVED -> AUTHORIZED -> PLANNED -> RUNNING
                                  |-> WAITING_TOOL -> RUNNING
                                  |-> WAITING_RETRY -> RUNNING
                                  |-> WAITING_APPROVAL -> RUNNING
                                  `-> SUCCEEDED / PARTIAL / FAILED
                                      CANCELLED / EXPIRED
```

每个 Run 至少保存：

- `runId`、`sessionId`、`actorId`、角色和授权决策。
- 业务场景、输入、deadline、step/token/cost budget。
- model、prompt、tool schema、retrieval index、policy 版本。
- 当前 step、attempt、lease、checkpoint 和幂等键。
- 证据引用、输出、失败类型和审计关联 ID。

RocketMQ 负责领域事件和异步集成，Redis 负责缓存和热会话，Nacos 负责发现和配置。它们的组合不自动等于 durable execution。

---

## 4. 技术选型原则

### 4.1 生产主线与挑战者

以下结论基于 2026-07-25 的官方仓库和文档。版本在真正引入时必须重新核验、锁定并保存兼容性报告。

| 能力 | 默认生产主线 | 挑战者 / 实验 | 决策 |
|---|---|---|---|
| Java LLM/Agent | LangChain4j Core | AgentScope Java 2.0、Spring AI | 用同一场景 PoC 后只保留一个核心框架 |
| MCP | 官方 MCP Java SDK | 框架内置 MCP 适配 | 协议层不自研 |
| Durable Workflow | Temporal Java SDK | AgentScope persistence、Restate PoC | 长任务阶段通过故障恢复实验后决策 |
| 模型访问 | OpenAI-compatible client | LiteLLM | 两个以上 Provider、配额或路由需求成立时引入 LiteLLM |
| RAG | LangChain4j + ES | 独立向量库 | 先做容量和检索质量基线 |
| Trace | OpenTelemetry context + 现有 SkyWalking | OpenInference Java | 统一 `traceId/runId/stepId` |
| LLM 可观测 | Langfuse | 自研 Dashboard | 通过 OTLP/HTTP 集成，避免 Java SDK 锁定 |
| CI 评测 | JUnit + Promptfoo HTTP | DeepEval、RAGAS、Inspect AI | 其他工具只做限时对比实验 |
| 策略 | Java deny-by-default allowlist | OPA | 策略复杂度出现后引入 OPA |
| PII | 最小字段 + Java 确定性脱敏 | Presidio | 中文数据集 PoC 达标后采用 |
| 本地推理 | 不作为 V1 前置 | vLLM | 数据主权或规模经济成立时引入 |

### 4.2 Java 框架决策实验

LangChain4j 和 AgentScope Java 2.0 各实现同一条“订单异常调查”最小链路，比较：

- Spring Boot 3.2.5 和 JDK 17 兼容性。
- 模型、结构化输出、MCP、SSE 和取消支持。
- 事件、权限、HITL、状态持久化和多租户隔离。
- OpenTelemetry、测试替身和故障注入能力。
- 依赖冲突、升级成本、文档和源码可维护性。
- 100 次固定评测下的质量、延迟、成本和恢复正确性。

当前默认 LangChain4j Core 的原因是轻量、Java 原生和便于控制边界。AgentScope Java 2.0 已提供 Harness、事件、权限和分布式状态能力，但 2.0 GA 时间较新，进入主线前必须完成运行历史和兼容性验证。不要同时维护两套生产 Agent Framework。

### 4.3 MCP 实现约束

- 使用官方 MCP Java SDK 和 Streamable HTTP。
- 当前项目仍使用 Jackson 2，优先选择 `mcp-core + mcp-json-jackson2`，避免默认聚合模块引入 Jackson 3 冲突。
- 固定 MCP 规范和 SDK 版本，升级前跑 conformance、契约和回归测试。
- MCP 是连接工具和上下文的协议，不是 Agent Runtime。
- Skill 是程序知识、指令、脚本和资源封装，与 MCP 是多对多关系。

### 4.4 Durable Workflow 约束

短同步查询可以先使用本地状态机和 MySQL Run Store。满足任一条件后进入 Temporal PoC：

- 任务持续时间经常超过 HTTP 生命周期。
- 需要跨重启恢复、定时等待、人工审批或外部回调。
- 工具调用需要可靠重试、取消和版本化恢复。
- 需要可查询的完整执行历史。

Temporal Workflow 只做确定性编排；LLM、MCP、数据库和网络调用全部放入 Activity。Activity 可能重复执行，业务副作用仍需幂等键。

---

## 5. 双轨学习与交付模型

```text
产品交付轨：D0 -> D1 -> D2 -> D3 -> D4 -> D5 -> D6 -> D7
                 |     |     |     |     |     |     |
知识学习轨：    L1 -> L2 -> L3 -> L4 -> L5 -> L6 -> L7
                 |     |     |     |     |     |     |
研究实验轨：     论文阅读 -> 最小复现 -> 对照实验 -> ADR -> 选择性进入产品
```

原则：

- 产品每 2 至 4 周必须出现可运行增量。
- 理论围绕当期工程问题学习，不阻塞产品首个闭环。
- 研究项目只有通过对照实验和 ADR 才能进入生产依赖。
- 安全、评测、可观测和成本从 D0 开始贯穿，不是最后补课。

---

## 6. 交付阶段

总周期按一人业余深度学习估算为 28 至 36 周。全职开发可压缩，但不能删除质量门禁。

### D0：业务、数据与规格基线，2 至 3 周

#### 交付

- 定义 3 个核心诊断场景、用户、业务价值和风险。
- 建立指标字典：口径、维度、时区、数据延迟、Owner、版本。
- 审计现有表、事件、Prometheus 指标和日志是否真的支持场景。
- 补齐支付失败原因、必要事件时间和诊断事实缺口的设计。
- 编写 C4 L1/L2、威胁模型、数据分类和首批 ADR。
- 建立版本化评测数据格式和 30 条基线案例。

#### 学习

- SDD（若指 Spec-Driven Development）。
- Agent、Workflow、RAG、BI 的边界。
- 基础概率、统计、离线评测和实验设计。

#### Gate

- 每个场景都有事实数据和人工基线；没有事实数据的场景不得开发。
- 指标查询的正确性可由普通 Java 测试独立验证。
- 权限、PII、失败行为和成本预算写入 Spec。

### D1：LLM 基础与最小纵向切片，3 周

#### 交付

- `my-xhs-ai` Maven 模块、健康检查和 Gateway 路由。
- 模型 Provider 抽象、超时、重试、结构化输出和 SSE。
- 一个固定指标工具：`order.query_volume`。
- 自然语言意图 -> 工具参数 -> 结果解释 -> 来源引用。
- JUnit 契约测试、Prompt 版本、最小 trace 和 token/cost 记录。

#### 学习

- Transformer、tokenization、embedding、attention 和上下文窗口。
- InstructGPT、结构化输出、Tool Calling、temperature 的真实作用。
- Prompt 模板、Few-shot、输出 Schema 和模型失败模式。

#### 实验

- 手写 Mini Transformer 仅作为学习 Lab，不进入生产服务。
- 比较两个模型在相同 30 条数据上的结构化输出可靠性。

#### Gate

- 关键数字与工具输出逐字段一致。
- 模型不可用时返回明确降级结果，不伪造数据。
- trace 中不包含密钥和未脱敏 PII。

### D2：可信工具层与 MCP，4 周

#### 交付

- `my-xhs-ai-mcp` 单体服务和官方 MCP Java SDK 集成。
- 订单、支付、库存三组固定工具。
- 工具 Schema、分页、最大窗口、deadline、错误分类和数据新鲜度。
- 只读账号、字段白名单、角色授权和审计。
- 对订单分片使用 ShardingSphere 逻辑数据源或汇总表，不让 LLM 扫描分片。
- MCP conformance、契约、越权、注入、超时和重连测试。

#### 学习

- MCP 架构、JSON-RPC、Tools/Resources/Prompts、Streamable HTTP。
- API 设计、Schema 演进、幂等、授权传播和数据血缘。
- Agent Skills 规范入门，理解它与 MCP 的多对多关系。

#### Gate

- LLM 无法提交任意 SQL、PromQL 或 ES DSL。
- 非授权工具调用由确定性策略 100% 拒绝并审计。
- 工具输出可被不依赖 LLM 的测试完全验证。

### D3：RAG 与上下文工程，3 至 4 周

#### 交付

- 指标字典、Runbook、业务 Schema 和故障手册知识库。
- 文档清洗、chunk、embedding、混合检索、rerank 和引用。
- ACL-aware retrieval、文档版本、索引版本和删除流程。
- 固定数据快照下的 recall@k、MRR/nDCG、引用正确性和答案忠实度基线。

#### 学习

- RAG 论文、BM25、dense retrieval、hybrid、RRF、rerank。
- Context Engineering：选择、压缩、隔离、预算和 provenance。
- 间接 Prompt Injection 和不可信检索内容边界。

#### Gate

- 回答中的业务口径必须可点击回原文。
- 更换 embedding 模型使用全量重嵌入或双索引迁移，不混用向量空间。
- 检索不到可信内容时拒答或声明不确定。

### D4：受限异常诊断 Agent，4 至 5 周

#### 交付

- Intent Router 区分指标、RAG、Workflow 和 Agent。
- `plan -> act -> observe` 有界循环。
- step、token、cost、deadline 和工具并发预算。
- 循环检测、无进展检测、证据与反证、部分结果和不确定性输出。
- 三个核心诊断场景端到端运行。
- LangChain4j 与 AgentScope Java 2.0 对照 PoC 和框架 ADR。

#### 学习

- ReAct、Plan-and-Execute、Reflexion、Self-Refine、ToT 的适用边界。
- Agent Harness：事件、中间件、权限、上下文压缩、工作区和 HITL。
- AgentScope Java 2.0 源码与 LangChain4j agentic 模块对比。

#### Gate

- 固定问题优先走确定性路径，不滥用 Agent。
- Agent 达到预算必须停止并返回已获得证据。
- 不能把相关性表述为已确认因果。
- 同一评测集重复运行并报告均值、方差和失败样本，不只展示最好结果。

### D5：Durable Execution 与记忆，4 周

#### 交付

- Run/Step 状态机、checkpoint、取消、重试、退避和错误分类。
- Worker 强杀、服务重启、重复消息和工具超时故障测试。
- Temporal PoC：Workflow、Activity、Signal、Query、版本和 replay。
- 会话摘要和上下文压缩。
- 明确区分 Run State、Conversation、Knowledge 和 Long-term Memory。

#### 学习

- Temporal durable execution 和确定性 replay。
- MemGPT/Letta、Mem0、MemoryOS、LoCoMo 评测。
- 记忆写入、检索、冲突、来源、有效期、遗忘、删除和隐私。

#### 实验

- 在 LoCoMo 子集比较原始历史、摘要、向量记忆和 MemoryOS。
- 只有业务评测证明长期记忆有增益，才实现可插拔 Memory Adapter。

#### Gate

- 工具执行成功但 Worker 崩溃时不产生不可接受的重复副作用。
- 旧 Workflow 可在新 Worker 上 replay 或有明确迁移方案。
- 用户记忆支持隔离、TTL、来源、删除和审计。

### D6：评测、安全与 AgentOps 深化，4 至 5 周

这些能力从 D0 已存在，本阶段负责规模化和攻防深化。

#### 交付

- PR、nightly、release 三层评测流水线。
- 至少 300 条分层案例：正常、边界、拒答、权限、故障、多轮和攻击。
- JUnit 确定性测试 + Promptfoo HTTP 黑盒评测和红队。
- Langfuse + OpenTelemetry 的 run/step/model/tool/retrieval trace。
- 质量、延迟、token、成本、工具成功率和恢复正确性 Dashboard。
- OWASP GenAI/LLM 威胁模型、间接注入、PII、最小权限和出口控制。
- 模型、Prompt、工具、索引、策略组成的 release bundle。

#### 学习

- AgentBench、RAGAS 指标、LLM-as-Judge 校准和人工一致性。
- OWASP GenAI Security、Prompt Injection、Excessive Agency、供应链风险。
- OpenTelemetry GenAI semantic conventions、SLO、错误预算和漂移。
- EU AI Act 适用性评估方法；不把技术清单等同于法律结论。

#### Gate

- 确定性权限、Schema、密钥扫描和关键契约测试全部通过。
- 概率型攻击使用 Attack Success Rate，不承诺“100% 识别”。
- 关键固定 PII 样例泄漏为 0，统计识别报告 precision/recall。
- 质量、成本和延迟没有超过批准的回归阈值。

### D7：部署、试点与作品集，3 至 4 周

#### 交付

- Docker Compose 本地环境和 K8s 部署清单。
- Secret、NetworkPolicy、read-only filesystem、resource limit、PDB 和健康检查。
- 容量模型、压力测试、备份恢复、RTO/RPO、灰度和回滚演练。
- Shadow -> Canary -> Pilot 发布流程。
- 用户手册、运维手册、故障手册和最终架构报告。
- 5 个演示视频和一套面试讲解材料。

#### 学习

- 模型服务、LiteLLM、vLLM、KV Cache、continuous batching 和 PagedAttention。
- Kubernetes、供应链安全、SBOM、容量规划和 FinOps。

#### Gate

- 新 release bundle 可一键回滚到上一版本。
- 故障演练覆盖模型限流、MCP 超时、Worker 崩溃、Redis/ES 不可用和数据延迟。
- SLO 基于真实负载重新校准，不使用无来源的行业数字。

---

## 7. 贯穿式 Definition of Done

每个阶段、每个业务能力都必须满足：

- [ ] 业务 Spec、输入输出、权限、失败行为和验收标准已定义。
- [ ] 代码、模型、Prompt、数据集、工具、索引和策略版本可追溯。
- [ ] 确定性单元测试、契约测试和权限测试通过。
- [ ] 新能力已加入版本化评测集并保存机器可读结果。
- [ ] model、retriever、agent 和 tool 调用具有 trace、延迟、token 和错误状态。
- [ ] trace、日志、评测产物不包含密钥和未脱敏 PII。
- [ ] 已定义 timeout、retry、cancel、fallback 和 partial-result 行为。
- [ ] 质量、延迟和成本相对批准基线没有越过回归阈值。
- [ ] ADR 记录采用、拒绝、替代和退出条件。
- [ ] 文档、Dashboard 和 Runbook 已同步。

---

## 8. 评测体系

### 8.1 评测对象

不能只评最终文本。至少评估：

| 层 | 评测内容 |
|---|---|
| Router | 是否选择正确的确定性、RAG、Workflow 或 Agent 路径 |
| Tool | 参数、授权、结果、错误语义、数据口径和新鲜度 |
| Retrieval | recall、ranking、ACL、引用和注入抵抗 |
| Trajectory | 调查步骤、冗余调用、循环、预算和恢复正确性 |
| Answer | 事实、来源、不确定性、可操作性和表达质量 |
| System | 延迟、成本、并发、可用性、降级、恢复和安全 |

### 8.2 数据集分层

- `smoke`：PR 运行，关键确定性场景，目标 30 至 50 条。
- `regression`：nightly 运行，业务、边界、故障和安全分层，目标 150 至 300 条。
- `release`：发布前运行，冻结数据快照、重复采样、隐藏测试和红队，300 条以上。
- `production`：脱敏失败样本、用户反馈和 shadow 数据，经审核后进入回归集。

### 8.3 不锁死唯一轨迹

Golden Dataset 保存目标事实、必须或禁止调用的能力、预算和约束，不默认要求唯一 Tool 调用序列。等价且安全的调查路径都可通过。

### 8.4 初始试点 SLO

以下是需要通过实测校准的试点目标，不是行业标准：

| 场景 | 初始目标 |
|---|---|
| 固定指标查询 | P95 完整响应小于 8 秒 |
| 异步诊断接收 | 2 秒内返回 `runId` |
| 诊断任务 | P95 在 60 秒内完成或返回部分结果 |
| 关键数字 | 与工具结果逐字段一致 |
| 来源覆盖 | 所有关键业务结论有 evidence reference |
| 未授权写调用 | 0 次成功 |
| 可恢复任务 | Worker 重启后继续或明确终止，无静默丢失 |
| 成本 | 先测分布，再按场景设预算和月度上限 |

---

## 9. 安全与治理基线

### 9.1 权限

- Gateway 完成身份认证，向下传递 actor、role、purpose 和 trace context。
- Tool Policy 默认拒绝，只允许显式注册的角色、工具、参数范围和数据字段。
- Prompt Injection 检测只是信号，不能替代确定性权限控制。
- V1 不注册写工具，因此模型无论如何被诱导都无法产生业务写副作用。

### 9.2 数据

- 数据最小化优先于“先取出再脱敏”。
- 工具只返回诊断所需聚合数据，不返回用户明细。
- PII、密钥和原始 Prompt 的 trace 采集策略单独配置。
- 记忆必须支持来源、目的、TTL、删除和用户隔离。

### 9.3 审计与可观测分离

- 调试 trace 可以采样和按策略删除。
- 合规审计记录必须完整、访问受控并按独立保留策略存储。
- 两者通过 `runId`、`traceId` 和 `policyDecisionId` 关联，但不能混为一个 ES 索引。

### 9.4 治理

- 模型、Prompt、工具、索引和策略作为不可分割 release bundle 发布。
- Provider 数据保留、训练使用、地域和密钥管理进入模型 ADR。
- EU AI Act 按部署地区、角色和风险分类评估，不默认所有 Agent 都是高风险系统。

---

## 10. 理论与论文路线

论文学习统一采用四步：**阅读 -> 最小复现 -> 与 my-xhs 对照 -> ADR/实验报告**。

### L1：模型基础

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| Attention Is All You Need | self-attention、位置编码、复杂度 | Mini Transformer + 图解 |
| InstructGPT | SFT、RM、RLHF 和局限 | 对齐方法比较笔记 |
| LoRA / QLoRA | 参数高效训练和量化 | 小模型分类实验 |

### L2：RAG 与上下文

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| RAG | parametric/non-parametric memory | my-xhs 业务知识 RAG |
| Context Engineering | 选择、压缩、隔离、预算 | 上下文策略 ADR |
| 检索评测 | recall@k、MRR、nDCG、faithfulness | 检索基准报告 |

### L3：Agent 推理与 Harness

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| ReAct | reasoning/action/observation loop | 有界调查 Agent |
| Reflexion | 反馈与经验反思 | 对照实验，不默认上线 |
| Tree of Thoughts | 搜索式推理与成本 | 小规模搜索 Lab |
| AgentScope Java 2.0 | event、middleware、permission、workspace、persistence | 与 LangChain4j 的 ADR |

### L4：状态与记忆

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| MemGPT/Letta | virtual context、stateful agent | 会话状态实验 |
| Mem0 | 记忆抽取与检索 | 三组 memory baseline |
| MemoryOS | 分层、热度、晋升、遗忘 | LoCoMo 子集复现 |
| Temporal | history、replay、activity、signal | Worker kill/recovery Lab |

MemoryOS 是值得学习的研究系统，但不把“Redis=CPU Cache、ES=RAM、MySQL=Disk”的类比直接当生产架构。

### L5：协议与程序知识

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| MCP Specification | host/client/server、tools/resources/prompts、transport、auth | Java MCP Server |
| Agent Skills Specification | SKILL.md、scripts、references、assets | 一个诊断 Skill |
| SDD | Spec、约束、验收和变更影响 | 每个能力的 Spec |

Skills 不取代 SDD，MCP 也不是 Skills 的底层或一对一执行载体。

### L6：评测、安全与运维

| 资料 | 必须掌握 | 产出 |
|---|---|---|
| AgentBench | 环境化 Agent 评测 | my-xhs eval taxonomy |
| Constraint Decay | 复杂约束下的退化 | 约束测试 Lab，限定论文适用范围 |
| OWASP GenAI Security | Prompt Injection、Excessive Agency、Data Leakage | 威胁模型和红队报告 |
| OpenTelemetry GenAI | trace 语义和版本成熟度 | Trace 规范 ADR |
| vLLM/PagedAttention | 推理吞吐和显存管理 | 可选 GPU Lab |

### L7：高级选修

| 主题 | 进入条件 | 产出 |
|---|---|---|
| Multi-Agent | 单 Agent 在隔离上下文或专业能力上出现可量化瓶颈 | 单 Agent vs subagent A/B |
| A2A | 出现独立部署、独立所有权或异构框架 Agent | 跨服务委派 Demo |
| Skills Registry | 内部 Skills 数量和团队协作出现治理需求 | 签名、版本、pinning、灰度设计 |
| Fine-tuning | Prompt、RAG、工具仍无法解决稳定领域模式 | 数据治理 + LoRA 实验 |
| Self-host Model | 数据主权或规模经济能覆盖 GPU 运维成本 | vLLM 容量和 TCO 报告 |

---

## 11. 面试领先路线

领先不来自背更多名词，而来自能拿出证据解释工程取舍。

### 11.1 必须能讲清的九个问题

1. 为什么“订单多少”不用 Agent，而“订单为什么下降”需要受限 Agent？
2. 如何保证 LLM 不生成错误业务数字？
3. MCP、Tool、Skill、Workflow 和 A2A 分别解决什么问题？
4. RocketMQ + Redis 为什么不等于 durable Agent Runtime？
5. Run State、Conversation、RAG Knowledge 和 Long-term Memory 有什么区别？
6. Agent 的非确定性如何做 CI/CD 回归和发布门禁？
7. Prompt Injection 无法彻底识别时，系统如何保证不越权？
8. 服务在工具调用后崩溃，如何避免重复副作用和丢失进度？
9. 如何在质量、延迟、成本和安全之间做可解释取舍？

### 11.2 作品集

- 一套 C4 架构图和不少于 10 个有效 ADR。
- LangChain4j vs AgentScope Java 2.0 对照报告。
- 一个官方 MCP Java SDK 服务和 conformance 结果。
- 一个 Temporal kill/recovery 故障实验。
- 一个 MemoryOS/LoCoMo 子集复现实验。
- 一套 300+ 案例评测集、Promptfoo 报告和红队报告。
- 一张 Langfuse trace 和一套 Grafana Dashboard。
- 一份成本、容量、SLO、灰度和回滚报告。
- 5 个业务演示视频和一篇完整技术复盘。

### 11.3 简历表达模板

不要写：

> 使用 LangChain4j、MCP、RAG、MemoryOS 和多 Agent 搭建智能平台。

应写成可验证结果：

> 设计并实现 my-xhs 运营异常诊断 Agent，将订单、支付、库存指标封装为默认拒绝授权的 MCP 工具；建立有界调查、证据引用、故障恢复和 300+ 用例评测门禁，并通过 Worker 强杀、工具超时和 Prompt Injection 实验验证系统在部分故障下可恢复且无未授权写操作。

最终数字必须来自真实实验，不预先编造提升百分比。

---

## 12. GitHub 与官方资料技术雷达

以下项目均在 2026-07-25 核对过官方仓库或规范。活跃不等于自动适合生产，所有版本在使用前重新确认。

### 生产候选

- [LangChain4j](https://github.com/langchain4j/langchain4j)：Java LLM、RAG、Tool 和 Agent 基础库，Apache-2.0。
- [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)：Java Agent Harness、事件、权限、状态和多 Agent，Apache-2.0；2.0 需先做稳定性 PoC。
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)：官方 Java SDK，MIT；使用 conformance suite 验证。
- [MCP Specification](https://modelcontextprotocol.io/specification/latest)：协议权威来源。
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)：代码式 durable workflow，Apache-2.0。
- [Langfuse](https://github.com/langfuse/langfuse)：LLM trace、数据集、评测和 Prompt 管理；核心 MIT，注意 `ee` 目录许可。
- [Promptfoo](https://github.com/promptfoo/promptfoo)：HTTP 黑盒评测和红队，MIT，运行在 CI/离线环境。
- [LiteLLM](https://github.com/BerriAI/litellm)：多模型代理、限额、路由和成本；仅在多 Provider 需求成立时引入。
- [OpenTelemetry GenAI Conventions](https://github.com/open-telemetry/semantic-conventions-genai)：GenAI trace 语义；仍需锁定约定版本。
- [OWASP GenAI Security](https://genai.owasp.org/llm-top-10/)：威胁建模基线，不是可执行防护产品。

### 研究与条件采用

- [MemoryOS](https://github.com/BAI-LAB/MemoryOS)：个性化长期记忆研究系统，先复现再决定。
- [Mem0](https://github.com/mem0ai/mem0)：长期记忆层，作为可替换 Adapter 比较。
- [Letta](https://github.com/letta-ai/letta)：stateful-agent 平台，只有选择完整平台路线时考虑。
- [Agent Skills Specification](https://agentskills.io/specification)：程序知识封装格式，可信内部 Skill 可试点。
- [A2A Protocol](https://github.com/a2aproject/A2A)：独立远程 Agent 互操作，出现跨团队边界后再引入。
- [Spring AI](https://github.com/spring-projects/spring-ai)：Spring 原生 AI 抽象；升级 Spring Boot 后重新评估兼容矩阵。
- [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)：阿里模型和 Graph 生态候选，作为 Java 对照实验。
- [RAGAS](https://github.com/vibrantlabsai/ragas)、[DeepEval](https://github.com/confident-ai/deepeval)、[Inspect AI](https://github.com/UKGovernmentBEIS/inspect_ai)：Python 离线评测候选，不同时全部引入。
- [vLLM](https://github.com/vllm-project/vllm)：自托管模型推理，GPU 和 TCO 成立后采用。
- [Microsoft Presidio](https://github.com/data-privacy-stack/presidio)：PII 检测与匿名化，中文效果必须自行评测。
- [Open Policy Agent](https://github.com/open-policy-agent/opa)：复杂策略集中决策，简单只读 allowlist 阶段不需要。

### 论文原文

- [ReAct](https://arxiv.org/abs/2210.03629)
- [Reflexion](https://arxiv.org/abs/2303.11366)
- [Tree of Thoughts](https://arxiv.org/abs/2305.10601)
- [RAG](https://arxiv.org/abs/2005.11401)
- [MemGPT](https://arxiv.org/abs/2310.08560)
- [MemoryOS](https://aclanthology.org/2025.emnlp-main.1318/)
- [AgentBench](https://arxiv.org/abs/2308.03688)
- [Constraint Decay](https://arxiv.org/abs/2605.06445)
- [LoRA](https://arxiv.org/abs/2106.09685)
- [QLoRA](https://arxiv.org/abs/2305.14314)
- [vLLM / PagedAttention](https://arxiv.org/abs/2309.06180)

---

## 13. 旧规划迁移

现有 `phase-00` 至 `phase-16` 文档保留为学习资料，但在完成事实核验和新路线映射前，不再代表实施顺序或生产选型。

| 旧内容 | 新位置 |
|---|---|
| Phase 0 架构 | D0，并重写数据、权限和运行时边界 |
| Phase 1 Python/ML | L1 Lab，不阻塞 D1 |
| Phase 2 LLM/Prompt | D1 + L1 |
| Phase 3 RAG | D3 + L2 |
| Phase 4 Memory | D5 + L4，MemoryOS 降为复现实验 |
| Phase 5 Agent Architecture | D4/D5，删除未经核实的“范式替代” |
| Phase 6 MCP | D2，先一个聚合 MCP，不默认十服务 |
| Phase 7 Multi-Agent/A2A | L7 高级选修 |
| Phase 8 Skills | L5，独立于 MCP 和 SDD |
| Phase 9 Evaluation | 从 D0 贯穿，D6 规模化 |
| Phase 10 AgentOps | 从 D1 贯穿，D6 深化 |
| Phase 11 Security | 从 D0 贯穿，D6 攻防深化 |
| Phase 12 Deployment | D7 |
| Phase 13 Governance | D0/D6/D7 贯穿 |
| Phase 14 Fine-tune | L7 条件选修 |
| Phase 15 Integration | 每阶段持续集成 + D7 最终验收 |
| Phase 16 AI Coding | 全程并行的开发效率与审查实践 |

迁移要求：

- 删除“某技术取代另一技术”的无来源结论。
- 技术数字必须附版本、日期和原始来源。
- 区分规范、框架、研究原型、产品宣传和项目自己的工程约束。
- 每篇理论文档增加“适用边界、反例、my-xhs 实验、是否进入主线”。

---

## 14. 里程碑总览

| 里程碑 | 周期 | 可演示结果 |
|---|---:|---|
| M0 可信问题定义 | 第 2 至 3 周 | 指标字典、数据审计、首批评测集 |
| M1 最小 AI 闭环 | 第 5 至 6 周 | 查询订单量并带来源回答 |
| M2 MCP 工具层 | 第 9 至 10 周 | 订单、支付、库存受控工具 |
| M3 业务知识 RAG | 第 12 至 14 周 | 带 ACL 和引用的口径问答 |
| M4 诊断 Agent | 第 16 至 19 周 | 三个异常调查场景 |
| M5 可恢复执行 | 第 20 至 23 周 | Worker kill 后恢复和 HITL |
| M6 生产门禁 | 第 24 至 28 周 | 300+ 评测、红队、trace、Dashboard |
| M7 试点与作品集 | 第 28 至 36 周 | 灰度、回滚、报告和演示视频 |

## 15. 最终原则

1. 先证明业务问题值得用 Agent，再设计 Agent。
2. 确定性系统负责数字、权限和副作用，LLM 负责理解、调查和解释。
3. 从第一条纵向切片开始做评测、安全、可观测和成本。
4. 一个单 Agent 和一个 MCP 服务能解决时，不引入多 Agent 和十个微服务。
5. 记忆、运行状态、知识和业务事实必须分离。
6. 论文用于形成假设，实验用于决定是否进入生产。
7. 工业级不是组件多，而是失败可控、结果可信、过程可追溯、版本可回滚。
8. 面试竞争力来自真实取舍和实验结果，不来自术语数量。
