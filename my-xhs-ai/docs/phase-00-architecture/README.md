# Phase 0: 系统架构蓝图

## 为什么第一个

项目不应该从 "Python 语法" 开始。Phase 0 建立全局认知——整个 AI 平台长什么样、用什么技术、怎么和 my-xhs 集成——有了这张图，后续每个 Phase 都知道自己在"拼哪块拼图"。

## 做什么

### 架构图（C4 模型）

```
L1: 系统上下文
┌──────────────────────────────────────────────────────┐
│  运营人员 ──→ my-xhs AI 平台 ──→ my-xhs 微服务体系     │
│              ┌──────────────┐  ┌───────────────────┐│
│              │ Agent 编排层  │  │ Nacos/Redis/ES    ││
│              │              │  │ MySQL×4/SkyWalking││
│              │ MCP Server×10│  │ RocketMQ/Sentinel ││
│              └──────────────┘  └───────────────────┘│
└──────────────────────────────────────────────────────┘

L2: 容器
┌──────────────────────────────────────────────────────┐
│  my-xhs-gateway (19000)                               │
│    └─→ /api/ai/** → my-xhs-ai (19020)                │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │ my-xhs-ai (19020)                            │    │
│  │ ├─ ChatController (SSE)                      │    │
│  │ ├─ HarnessAgent/Runtime                     │    │
│  │ ├─ Memory (Redis+ES+MySQL)                   │    │
│  │ ├─ EvalEngine / AgentOps                    │    │
│  │ └─ Security (Injection/PII/Permission)       │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  MCP Server 集群 (19021-19030, 10 个独立服务)              │
│  ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐          │
│  │order ││user  ││pay   ││inven ││log   │          │
│  │19021 ││19022 ││19023 ││19024 ││19025 │          │
│  └──────┘└──────┘└──────┘└──────┘└──────┘          │
│  ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐          │
│  │content││prod  ││coupon││analyt││search│          │
│  │19026  ││19027 ││19028 ││19029 ││19030 │          │
│  └──────┘└──────┘└──────┘└──────┘└──────┘          │
└──────────────────────────────────────────────────────┘
```

### 技术选型决策记录（ADR）

| 决策 | 选型 | 理由 | 备选 |
|------|------|------|------|
| LLM Provider | DeepSeek V3 | 中文好+便宜(¥1/M token)+无需翻墙 | OpenAI(贵)/Ollama(慢) |
| Agent 主框架 | LangChain4j | Java 原生+社区成熟+Spring Boot 集成 | AgentScope(深学用) |
| Agent 深学框架 | AgentScope 2.0 | Harness 层工程化深度 | — |
| 向量存储 | ES dense_vector | 复用 my-xhs 已有 ES(19200) | PGVector/Milvus |
| 分布式会话 | Redis | 复用 my-xhs 已有 Redis(16381) | — |
| 任务编排 | RocketMQ | 复用 my-xhs 已有 | Kafka |
| 服务发现 | Nacos | 复用 my-xhs 已有 | — |
| 限流熔断 | Sentinel | 复用 my-xhs 已有 | — |
| 链路追踪 | SkyWalking | 复用 my-xhs 已有 | — |

### 开发环境

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 主语言 |
| Spring Boot | 3.2.5 | 运行框架 |
| LangChain4j | 1.0.0-beta2 | Agent 实战框架 |
| AgentScope | 2.0.0 | Agent 深学框架 |
| Python | 3.10+ | 辅助（实验/微调） |
| Docker | latest | 容器化+沙箱 |
| Maven | 3.x | 构建管理 |

### 核心约束

1. **只读**：Agent 只查询、不修改 my-xhs 数据
2. **复用优先**：优先复用 my-xhs 已有中间件（Nacos/Redis/ES/RocketMQ/Sentinel/SkyWalking）
3. **新增组件**：LiteLLM（LLM 网关, Phase 10）+ Langfuse（Agent 可观测, Phase 10），其余不引入
4. **Java 主线**：所有业务代码用 Java
5. **Python 辅助**：仅用于 HuggingFace 实验 + LoRA 微调
6. **MCP 标准化**：所有工具通过 MCP 协议暴露

### 模块依赖图

```
my-xhs-ai (19020)                ← Agent 编排层
  ├── Nacos 服务发现
  ├── Redis (16381) 会话快照
  └── RocketMQ 任务编排
       │
       ├─→ order-mcp (19021)     → MySQL 13308 (my_xhs_order, 4分片)
       ├─→ user-mcp (19022)      → MySQL 13306 (my_xhs_user)
       ├─→ payment-mcp (19023)   → MySQL 13308 (my_xhs_payment)
       ├─→ inventory-mcp (19024) → MySQL 13309 (my_xhs_inventory)
       ├─→ log-mcp (19025)       → ES 19200 (Logstash 日志)
       ├─→ content-mcp (19026)   → MySQL 13307 (my_xhs_content)
       ├─→ product-mcp (19027)   → MySQL 13307 (my_xhs_product)
       ├─→ coupon-mcp (19028)    → MySQL 13307 (my_xhs_coupon)
       ├─→ analytics-mcp (19029) → MySQL 13306 (my_xhs_analytics)
       └─→ search-mcp (19030)    → ES 19200 + MySQL 13307 (t_hot_search)
```

## 文档

```
docs/phase-00-architecture/
├── README.md                      ← 本文件
├── 01-system-context.md           ← C4 L1 系统上下文图
├── 02-container-diagram.md        ← C4 L2 容器图
├── 03-component-diagram.md        ← C4 L3 组件图（my-xhs-ai 内部）
├── 04-tech-decisions.md           ← ADR 技术选型决策记录
├── 05-my-xhs-integration.md       ← 与 my-xhs 的 7 组件映射详图
└── 06-dev-env-setup.md            ← 开发环境搭建指南
```
