# AI Agent 关键论文清单（2024-2026）

基于 AI Insight 121 篇综述 + AgentGuide 21 篇记忆论文 + Arxiv 最新论文。

---

## 一、综述论文（先读这些建立全局视野）

| 论文 | 年份 | 来源 | 核心贡献 |
|------|------|------|---------|
| **AI Agent 最新发展综述** | 2026-05 | AI Insight (121篇) | 5 维度全景：感知推理/记忆/多智能体/工具具身/评估安全 |
| **Memory in the Age of AI Agents** | 2025-12 | arxiv:2512.13564 | 首个 Agent 记忆统一分类体系（形式/架构/生命周期三维度） |
| **Architectural Design Decisions in AI Agent Harnesses** | 2026-04 | arxiv:2604.18071 | 对 70 个公开 Agent 系统的实证研究，Agent=Model+Harness 范式 |

---

## 二、按 Phase 分派的关键论文

### Phase 1: ML 基础 + 神经网络

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| Attention Is All You Need | 2017 | NeurIPS | Transformer 原始论文——一切 LLM 的起点 |
| Deep Residual Learning for Image Recognition (ResNet) | 2016 | CVPR | 残差连接——Transformer 的核心组件 |

### Phase 2: LLM 原理 + Prompt 工程

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| Language Models are Few-Shot Learners (GPT-3) | 2020 | NeurIPS | 证明了 prompt 可以替代微调 |
| Chain-of-Thought Prompting Elicits Reasoning | 2022 | NeurIPS | CoT——推理链的起源 |
| Training language models to follow instructions (InstructGPT) | 2022 | NeurIPS | RLHF 的工业级首次应用 |
| Direct Preference Optimization (DPO) | 2023 | NeurIPS | 替代 RLHF，更简单的偏好对齐 |
| DeepSeek-R1: Incentivizing Reasoning Capability in LLMs via RL | 2025-01 | arxiv | 纯 RL 推理路线，可复现 |

### Phase 3: RAG + 上下文工程

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks | 2020 | NeurIPS | RAG 原始论文 |
| Lost in the Middle | 2023 | arxiv | 长上下文中模型忽略中间信息——影响 chunk 排序策略 |
| Self-RAG: Learning to Retrieve, Generate, and Critique | 2023 | arxiv | Agent 自主决定何时检索 |

### Phase 4: Agent 记忆系统

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| MemGPT: Towards LLMs as Operating Systems | 2023 | arxiv | OS 虚拟内存思想首次用于 LLM 记忆 |
| **MemoryOS** | 2025 | EMNLP (Oral) | OS 级记忆管理：分层存储+热数据提升+缓存替换 |
| Mem0: Memory Layer for AI Agents | 2025 | — | 通用记忆层（47K GitHub stars） |
| Zep: Temporal Knowledge Graph for Agent Memory | 2025 | — | 时间感知记忆，事实版本链 |

### Phase 5: Agent 架构（推理与行动）

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| **ReAct: Synergizing Reasoning and Acting in Language Models** | 2022 | ICLR 2023 | Agent 循环的奠基论文 |
| Tree of Thoughts: Deliberate Problem Solving | 2023 | NeurIPS | 多路径探索推理 |
| **Reflexion: Language Agents with Verbal Reinforcement Learning** | 2023 | NeurIPS | 失败后自我反思——不是盲重试 |
| Self-Refine: Iterative Refinement with Self-Feedback | 2023 | NeurIPS | 每次输出后自我改进 |
| Voyager: An Open-Ended Embodied Agent with LLMs | 2023 | NeurIPS | Minecraft 中自主探索的 Agent |
| **Constraint Decay: The Fragility of LLM Agents** | 2025 | arxiv | Happy path 好但约束条件静默失败 |

### Phase 6-8: 多 Agent + MCP + Skills

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| CAMEL: Communicative Agents for "Mind" Exploration | 2023 | NeurIPS | 角色扮演多 Agent 对话 |
| AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation | 2023 | arxiv | 微软多 Agent 框架 |
| MetaGPT: Meta Programming for Multi-Agent Collaborative Framework | 2023 | arxiv | 模拟软件公司的 Agent 团队 |
| MCP: Model Context Protocol | 2025 | Anthropic | 工具标准化协议（已捐 Linux Foundation） |

### Phase 9-11: 评测 + 安全 + 治理

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| **SWE-bench: Can Language Models Resolve Real-World GitHub Issues?** | 2024 | ICLR | 编码 Agent 行业标准基准 |
| **GAIA: A Benchmark for General AI Assistants** | 2023 | arxiv | 通用 AI 助手基准 |
| AgentBench: Evaluating LLMs as Agents | 2023 | arxiv | 8 环境多维度 Agent 评测 |
| Constitutional AI: Harmlessness from AI Feedback | 2022 | arxiv | 宪法 AI——安全对齐的另一种路线 |
| OWASP Top 10 for LLM Applications | 2025 | OWASP | LLM 安全威胁分类 |

### Phase 12-14: 部署 + 微调

| 论文 | 年份 | 来源 | 为什么重要 |
|------|------|------|-----------|
| **LoRA: Low-Rank Adaptation of Large Language Models** | 2021 | ICLR 2022 | 参数高效微调的基石 |
| QLoRA: Efficient Finetuning of Quantized LLMs | 2023 | NeurIPS | 4-bit LoRA |
| vLLM: Easy, Fast, and Cheap LLM Serving with PagedAttention | 2023 | SOSP | KV Cache 分页管理 |

---

## 三、必读优先级

| 优先级 | 论文 | 对应 Phase |
|:---:|------|-----------|
| ⭐⭐⭐ | Attention Is All You Need | Phase 2 |
| ⭐⭐⭐ | ReAct | Phase 5 |
| ⭐⭐⭐ | Reflexion | Phase 5 |
| ⭐⭐⭐ | Constraint Decay | Phase 9 |
| ⭐⭐⭐ | Memory in the Age of AI Agents (综述) | Phase 4 |
| ⭐⭐⭐ | Architectural Design Decisions in AI Agent Harnesses | Phase 5 |
| ⭐⭐ | MemGPT | Phase 4 |
| ⭐⭐ | MemoryOS | Phase 4 |
| ⭐⭐ | SWE-bench | Phase 9 |
| ⭐⭐ | LoRA | Phase 14 |
| ⭐ | Self-RAG | Phase 3 |
| ⭐ | AutoGen / MetaGPT | Phase 7 |
