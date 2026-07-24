# GitHub 工具生态（2026 年 7 月调研 v2）

## Agent 记忆系统

| 工具 | Stars | 定位 | 代数 |
|------|-------|------|:---:|
| Mem0 | 47K | 通用记忆平台，向量+图+KV | 第二代 |
| Letta (MemGPT) | 12K | 分层记忆，完全自托管 | 第二代 |
| Zep | 8K | 时间感知记忆，事实版本链 | 第二代 |
| LangMem | 3K | LangGraph 原生记忆 | 第二代 |
| **MemoryOS** (BAI-LAB) | — | **OS 级记忆管理**，EMNLP 2025 Oral | **第三代** |

## Agent 架构（2026 新范式）

| 范式 | 代表 | 定位 |
|------|------|------|
| **控制平面** | OpenAI Symphony | Jira/Linear 看板 = Agent 任务调度器 |
| **分布式运行时** | Google Agent Executor | 事件日志+快照=可恢复执行 |
| **沙箱基础设施** | GKE Agent Sandbox (GA) | Agent 专用隔离执行环境 |
| **Agent OS** | AIOS / OpenOS | Agent 原生操作系统 |

## Agent Skills

| 工具 | Stars | 定位 |
|------|-------|------|
| **Anthropic Skills Repo** | **138K** (3天) | 官方 Agent Skills 仓库，范式转移信号 |
| Agent Skills 概念 | — | Prompt工程→Skill工程 |

## AgentOps / 可观测

| 工具 | Stars | 定位 |
|------|-------|------|
| LiteLLM | 54.4K | LLM 统一网关 |
| Langfuse | 31.7K | 全栈可观测+评测+Prompt管理 |
| Promptfoo | 23.5K | 红队测试 + 安全扫描 |
| Opik (Comet) | 20.8K | 评测+追踪一体化 |
| DeepEval | 17.1K | CI 集成评测，40+ 指标 |

## 安全 / Guardrails

| 工具 | 定位 |
|------|------|
| Guardrails AI | LLM 输出验证 |
| NeMo Guardrails (NVIDIA) | 可编程对话护栏 |
| Lakera Guard | 实时注入/泄露检测 |
| Invariant | MCP 级别安全护栏 |

## 合规与治理（2026 新增）

| 要求 | 生效时间 | 说明 |
|------|---------|------|
| EU AI Act | **2026 年 8 月** | Agent 必须有审计追踪+权限管控+风险分级 |

## 本地部署 / 推理

| 工具 | Stars | 定位 |
|------|-------|------|
| Ollama | 162K | 本地 LLM 一键部署 |
| vLLM | — | 高吞吐推理引擎 |
| Qwen3.6-Plus | — | 1M 上下文，**原生 MCP** |

## SDD / AI 编程

| 工具 | 定位 |
|------|------|
| Claude Code | SWE-bench 80.9% |
| OpenSpec | 开源 SDD 框架 |
| Cursor | IDE 原生 Agent |

## Agent 框架

| 框架 | 语言 | 定位 |
|------|------|------|
| LangChain4j | **Java** | Java AI 主战框架 |
| LangChain | Python/JS | 最广泛采用的框架 |
| LangGraph | Python | 有状态 Agent 编排 |
| CrewAI | Python | 基于角色的多 Agent |
| Semantic Kernel | Java/C#/Python | 微软企业级 |
| Dify | — | 开源 LLMOps 平台 (130K⭐) |

## MCP 生态

- 2000+ MCP Server 收录
- 协议已捐 Linux Foundation
- 传输：STDIO / SSE / Streamable HTTP

## 协议与标准

| 协议 | 提出者 | 定位 |
|------|--------|------|
| MCP | Anthropic | AI↔工具标准化接口 |
| A2A | Google | Agent↔Agent 通信协议 |
