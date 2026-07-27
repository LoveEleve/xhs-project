# my-xhs-ai 完整规划 v4（历史版本）

## 目标

**Java+AI 全栈工程师** — 理解 2026 年 Agent 架构全景，能基于 my-xhs 微服务体系构建生产级 AI 平台。

---

## 2026 年 Agent 架构全景（探索结论）

2026 年 5 月，Agent 架构发生了根本性移位。旧概念降级，新范式崛起：

| 旧概念 | 2026 地位 | 新范式 | 代表 |
|--------|---------|--------|------|
| ReAct Loop | 底层原子能力 | 控制平面 (Control Plane) | OpenAI Symphony |
| LangGraph | 开发工具层 | 分布式运行时 (Distributed Runtime) | Google Agent Executor |
| MCP | 连接层协议 | Agent Skills 技能工程 | Anthropic Skills Repo (138K⭐) |
| SDD | 被吸收 | Agent OS | MemoryOS (EMNLP 2025 Oral) |
| Futurum 4层记忆 | 理论模型 | 沙箱基础设施 | GKE Agent Sandbox (GA) |
| 传统评测 | 单元维度 | 5 维评测体系 + Constraint Decay | 行业标准 |
| 安全即后加 | 外围防线 | EU AI Act 合规治理 | 2026 年 8 月生效 |

---

## 14 个 Phase（重新组织）

```
Phase 1-3: AI 基础三层
┌───────────────────────────┐
│ 1. Python + ML 基础        │  ← 不变
│ 2. LLM 原理 + Prompt       │  ← 不变
│ 3. RAG + 上下文工程         │  ← 不变
└───────────────────────────┘

Phase 4-5: Agent 核心引擎（重写）
┌───────────────────────────┐
│ 4. Agent 记忆系统 (重写)    │  ← MemoryOS + OS 级记忆管理
│ 5. Agent 架构全貌 (重写)    │  ← 控制平面 + 分布式运行时 + Skills
└───────────────────────────┘

Phase 6-9: 企业级基础���施
┌───────────────────────────┐
│ 6. MCP 协议 + 工具生态      │
│ 7. 多 Agent + A2A           │
│ 8. Agent Skills 技能工程     │  ← 新增！取代 SDD
│ 9. 评测体系 (5 维 + Constraint Decay) │ ← 扩展
└───────────────────────────┘

Phase 10-14: 生产与治理
┌───────────────────────────┐
│ 10. AgentOps + 可观测       │
│ 11. AI 安全 + Guardrails    │  ← EU AI Act 合规
│ 12. 部署 + 推理 + 沙箱       │  ← 加沙箱
│ 13. 企业治理 + 合规         │  ← 新增！
│ 14. 微调 + 领域适配          │
└───────────────────────────┘
```

变化总结：
- Phase 4 (Agent记忆) → 加入 MemoryOS OS 级记忆
- Phase 5 (Agent架构) → 从"ReAct+Graph"升级为"控制平面+分布式运行时+Skills"
- Phase 8 (新增) → Agent Skills 取代原 SDD
- Phase 13 (新增) → 企业治理 + EU AI Act 合规
- Phase 9 (评测) → 从基础评测升级为 5 维评测 + Constraint Decay

---

## Phase 4: Agent 记忆系统（重写）

### 前置依赖
- Phase 3 (RAG) — 检索是记忆的基础

### 为什么重写
之前只覆盖了 Mem0/Zep/Letta + Futurum 四层模型。但 2026 年的记忆系统已经进化到 OS 级别——MemoryOS 把 OS 内存管理（分层存储、热数据提升、缓存替换、分页）用到了 AI Agent 记忆上。

### 学什么

#### 1. 记忆系统的三次进化
- 第一代：简单 Message 列表（LangChain ChatMessageHistory）
- 第二代：向量存储（Mem0, Zep, Letta）— 检索式记忆
- 第三代：OS 级记忆管理（MemoryOS, AIOS）— 不只是"存什么"，而是"怎么调度记忆资源"

#### 2. MemoryOS 深度（EMNLP 2025 Oral）
- 架构理念：把 OS 内存管理（分层存储、热数据提升、缓存替换、分页）应用到 Agent 记忆
- 三层分层：短期（CPU 缓存）→ 中期（RAM，热数据阈值 `mid_term_heat_threshold`）→ 长期（磁盘，用户画像+知识图谱）
- 四大模块：Storage（存储交互）→ Updating（层级间流转）→ Retrieval（全网检索）→ Generation（上下文生成）
- 性能：LoCoMo 基准 F1 +49.11%，BLEU-1 +46.18%
- MCP 集成：`MemoryOS-MCP` 作为标准工具被 Claude Desktop 等客户端调用

#### 3. AIOS（Agent Operating System）
- 把 LLM 嵌入 OS 内核——不只是记忆，而是调度文件系统/进程/网络等全部 OS 资源
- Agent 的"进程调度"：多个 Agent 怎么共享 CPU/内存/IO

#### 4. OpenOS（Agent-Native OS）
- 以 AI Agent 为原生执行单元的操作系统
- 对比：传统 OS 以进程/线程为执行单元，OpenOS 以 Agent 为执行单元

#### 5. 在 Java 中实现 OS 级记忆
- 工作记忆：Redis Hash + TTL（类比 CPU 缓存）
- 热数据管理：Redis Sorted Set，按访问频率排序，冷数据降级到 MySQL
- 中期记忆：ES indexed + heat_score 字段
- 长期记忆：MySQL + ES 混合（结构化 + 可检索）
- 缓存替换：LRU/LFU 策略，内存不足时的淘汰机制

### 做什么

```
docs/phase-04-agent-memory/
├── README.md
├── 01-memory-evolution.md         # 记忆系统三次进化
├── 02-memoryos-deep-dive.md       # MemoryOS 架构深度（OS 级记忆）
├── 03-aios-openos.md              # AIOS + OpenOS Agent OS
├── 04-mem0-zep-letta-compare.md   # 第二代工具对比（保留）
├── 05-java-os-memory-impl.md      # Java 实现 OS 级记忆管理
└── 06-locommo-benchmark.md        # LoCoMo 基准实验
```

---

## Phase 5: Agent 架构全貌（重写）

### 前置依赖
- Phase 2 (LLM 原理) — 理解 Agent 如何使用模型
- Phase 4 (记忆) — Agent 的记忆基础设施

### 为什么重写
之前 Phase 5 叫"Agent 基础 (LangChain4j)"，内容只有 ReAct + Tool Calling。但 2026 年 Agent 架构的核心已从单一循环转向控制平面 + 分布式运行时 + Skills。LangChain4j 只是实现工具之一。

### 学什么

#### 1. 控制平面（Control Plane）
- 概念：不再由 Agent 自己决定下一步——而是由一个"控制平面"（项目管理看板）来调度
- OpenAI Symphony：Linear/Jira 看板 = Agent 的控制平面
  - 每个开放任务 → 分配给独立 Agent workspace
  - 任务树 + 依赖：复杂任务先拆成子任务（调研 → 规划 → 执行 → 测试）
  - 人机共治：人在计划/代码变更/风险动作节点评审
- 为什么 Loop 降级了：Loop 只解决"当前这一步做什么"——控制平面解决"所有任务怎么分配和追踪"

#### 2. 分布式运行时（Distributed Runtime）
- 概念：Agent 的执行不再是单次 API 调用——而是长时间运行的分布式任务
- Google Agent Executor：
  - 事件日志（Event Log）：记录每一步操作
  - 快照（Snapshotting）：保存当前状态
  - 可恢复执行：Agent 暂停/恢复/重新部署不丢状态
- 与你 my-xhs 的关系：
  - Nacos = Agent 的服务发现 ← 你已有
  - RocketMQ = Agent 的异步任务编排 ← 你已有
  - Redis = Agent 的分布式会话恢复 ← 你已有

#### 3. Agent Skills（技能工程）
- Anthropic 2026 年 5 月开源，138K stars 3 天
- 核心思想：AI 开发从"提示词工程"进入"技能工程"
- Skill = 一个可复用、可组合、可版本化的功能模块
- SKILL.md 格式：名称、描述、输入参数、输出格式、执行步骤、示例
- 与你 my-xhs 的关系：每个 MCP Server 就是一个 Skill

#### 4. Loop + Graph 的正确位置
- CoT/ToT/ReAct/Reflexion/Self-Refine：这是 Agent 的"推理策略"，不是架构
- LangGraph StateGraph：这是 Agent 的"编排工具"，不是架构
- 在控制平面分配任务后，每个 Agent 内部可以用 Graph 编排，用 Loop 推理——但它们只是实现细节

#### 5. 在 my-xhs 中实现控制平面
- 控制平面 = 基于 XXL-Job 的任务看板
- 每个 Agent 任务 = 一个 RocketMQ 消息
- 分布式运行时 = Redis + RocketMQ + Nacos 的组合
- Agent Skills = MCP Server 集群

### 做什么

```
docs/phase-05-agent-architecture/
├── README.md
├── 01-control-plane.md           # 控制平面架构（OpenAI Symphony）
├── 02-distributed-runtime.md     # 分布式运行时（Google Agent Executor）
├── 03-agent-skills.md            # 技能工程（Anthropic Skills Repo）
├── 04-loop-graph-position.md     # Loop+Graph 的正确位置
├── 05-myxhs-control-plane.md     # 在 my-xhs 中实现控制平面
└── 06-langchain4j-usage.md       # LangChain4j 作为实现工具
```

---

## Phase 8: Agent Skills 技能工程（新增）

### 前置依赖
- Phase 5 (Agent 架构) — 理解 Skills 在架构中的位置
- Phase 6 (MCP) — Skills 的底层技术是 MCP

### 为什么新增
Anthropic 2026 年 5 月开源的 Agent Skills 仓库（138K stars/3天）标志着范式转移——从"写 Prompt"到"写 Skill"。你的 my-xhs MCP Server 本质上是 Skills——这个 Phase 把它们标准化为可复用的技能模块。

### 学什么

#### 1. Agent Skills 核心概念
- Skill = 独立的、可复用、可版本化的功能模块
- SKILL.md 规范：名称、描述、输入参数、输出格式、执行步骤、前置条件、示例
- Agent 如何自动选择 Skills：基于任务描述 + Skill 语义匹配

#### 2. Anthropic Skills Repo 分析
- 仓库结构：按领域组织（coding, data-analysis, web-browsing, document-processing）
- 每个 Skill 包含：SKILL.md + 实现代码 + 测试用例
- Skill 生命周期：create → validate → publish → version → deprecate

#### 3. Skills 市场与生态
- Agent Skills = Agent 的 "npm registry"
- Skills 的发布、发现、安装、更新机制
- Skills 版本兼容性管理

#### 4. 在 my-xhs 中实现 Skills 注册中心
- Nacos 配置中心管理 Skills 元数据
- MCP Server = Skill 的执行载体
- Skills 注册/发现/调用/更新全流程

### 做什么

```
docs/phase-08-agent-skills/
├── README.md
├── 01-skills-paradigm.md         # 从 Prompt 到 Skill 的范式转移
├── 02-anthropic-repo-analysis.md # Anthropic Skills 仓库深度分析
├── 03-skill-lifecycle.md         # Skill 生命周期管理
├── 04-myxhs-skill-registry.md    # my-xhs Skills 注册中心实现
└── 05-skill-market-ecosystem.md  # Skills 市场与生态
```

---

## Phase 9: 评测体系（扩展为 5 维）

### 新增内容

#### Constraint Decay 现象
- 论文发现：Agent 在 happy path 上表现好，但在约束条件下静默失败
- 关键洞察：如果 Agent 通过 99% 简单测试但只有 70% 约束测试——它不生产就绪

#### 5 维评测框架
1. 任务完成准确率 — 指定预期结果 + 金额 + 约束
2. 约束满足度 — 空输入/null/边界/冲突指令/token 溢出
3. 故障鲁棒性 — 注入工具超时/限流/空结果/malformed JSON
4. 成本与延迟 — tokens/任务、调用次数、端到端延迟、每次完成成本
5. 安全护栏 — 注入抵抗、数据泄露、行为范围、偏见/公平

#### 评测金字塔
- 底层：静态分析（检查 Prompt/Schema/Config）— 免费
- 单元测试（Prompt→预期输出，mock 工具）— 快速
- 集成测试（Agent+真实工具，mock 外部 API）— 中等
- 顶层：端到端测试（全流程+真实工具）— 慢/贵

#### 生产监控 Scorecard
| 指标 | 目标 | 告警 |
|------|------|------|
| 任务完成率 | >95% | <90% |
| 约束违反率 | <1% | >3% |
| 平均延迟 | <5s | >15s |
| 每次任务成本 | <$0.10 | >$0.50 |
| 错误级联率 | <5% | >15% |

---

## Phase 13: 企业治理 + 合规（新增）

### 为什么新增
EU AI Act 2026 年 8 月生效——Agent 必须有审计追踪、权限管控、风险分级。这不是"可选的加分项"，而是合规要求。

### 学什么
- EU AI Act 对 AI Agent 的具体要求
- 审计追踪：谁、何时、做了什么、为什么
- 权限管控：最小权限原则、职责分离
- 风险分级：不可接受/高风险/有限风险/最低风险
- 数据主权：数据存储地点、跨境传输限制
- 人类监督：高风险 AI 系统必须有人类监督能力

### 做什么
```
docs/phase-13-governance/
├── README.md
├── 01-eu-ai-act.md               # EU AI Act 核心要求
├── 02-audit-trail.md             # Agent 审计追踪实现
├── 03-permission-model.md        # 权限模型与职责分离
├── 04-risk-classification.md     # Agent 风险分级
└── 05-data-sovereignty.md        # 数据主权与合规
```

---

## 完整 14 Phase 总览 v4

| # | Phase | 变化 | 目录 | 核心 |
|---|-------|:---:|------|------|
| 1 | Python + ML 基础 | — | [phase-01-python-ml/](phase-01-python-ml/README.md) | HuggingFace 环境 + 手写神经网络 |
| 2 | LLM 原理 + Prompt | — | [phase-02-llm-prompt/](phase-02-llm-prompt/README.md) | Mini Transformer + Prompt 工程化 |
| 3 | RAG + 上下文工程 | — | [phase-03-rag-context/](phase-03-rag-context/README.md) | 完整 RAG 管道 + my-xhs 知识库 |
| 4 | **Agent 记忆系统** | 🔄 | [phase-04-agent-memory/](phase-04-agent-memory/README.md) | MemoryOS OS 级记忆 + 三次进化 |
| 5 | **Agent 架构全貌** | 🔄 | [phase-05-agent-architecture/](phase-05-agent-architecture/README.md) | 控制平面 + 分布式运行时 + Skills |
| 6 | MCP 协议 + 工具生态 | — | [phase-06-mcp/](phase-06-mcp/README.md) | 10 个独立 MCP Server |
| 7 | 多 Agent + A2A | — | [phase-07-multi-agent/](phase-07-multi-agent/README.md) | Meta Planner + Agent 协作 |
| 8 | **Agent Skills 技能工程** | ✅ | [phase-08-agent-skills/](phase-08-agent-skills/README.md) | Anthropic Skills Repo (138K⭐) |
| 9 | **评测体系 (5 维)** | 🔄 | [phase-09-evaluation/](phase-09-evaluation/README.md) | Constraint Decay + 评测金字塔 |
| 10 | AgentOps + 可观测 | — | [phase-10-agentops/](phase-10-agentops/README.md) | LiteLLM + Langfuse + 全链路追踪 |
| 11 | AI 安全 + Guardrails | — | [phase-11-security-guardrails/](phase-11-security-guardrails/README.md) | GuardrailsAI/Lakera/EU AI Act |
| 12 | 部署 + 推理 + 沙箱 | 🔄 | [phase-12-deployment-inference/](phase-12-deployment-inference/README.md) | vLLM + GKE Sandbox + K8s |
| 13 | **企业治理 + 合规** | ✅ | [phase-13-governance/](phase-13-governance/README.md) | EU AI Act + 审计 + 风险分级 |
| 14 | 微调 + 领域适配 | — | [phase-14-finetune/](phase-14-finetune/README.md) | LoRA/QLoRA + 数据工程 |

| 0 | 🔵 **系统架构蓝图** | [phase-00-architecture/](phase-00-architecture/README.md) | — | 1周 |
| 15 | 🔵 **系统集成验收** | [phase-15-integration/](phase-15-integration/README.md) | P1-14 | 2-3周 |
| 16 | 🔵 **AI 编程工具效能** | [phase-16-ai-coding/](phase-16-ai-coding/README.md) | P1 | 1-2周 |

**总计**：17 个 Phase（Phase 0 + 15 个学习 Phase + Phase 15 验收），约 49-67 周

---

## 参考资源

- [16 本参考书清单](reference/books.md)
- [GitHub 工具生态一览](reference/tools.md)
- [论文索引](reference/papers.md) / [已下载论文](reference/papers/)
- [my-xhs 业务域全景](reference/business-domain.md)
