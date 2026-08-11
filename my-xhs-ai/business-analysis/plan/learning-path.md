# 学习路线（零基础 → 能独立交付 Agent）

> 前提：**你目前没有 AI/Agent 基础**。本路线与交付阶段(D0-D7)并行，边做边学，理论围绕当期工程问题。
> 原则：① 学习不阻塞交付 ② 概念先在 `concepts-glossary.md` 查 ③ 每个阶段学的东西立刻用在当期代码里。

---

## 阶段总览（学习轨 ↔ 交付轨）

| 学习阶段 | 对应交付 | 学什么 | 产出 |
|:--:|:--:|------|------|
| L0 热身 | D0 | 本词典概念扫一遍 + 环境 | 看懂所有名词 |
| L1 模型基础 | D1 | Transformer/Token/结构化输出/Tool Calling | 调通第一个模型 |
| L2 工具与协议 | D2 | MCP/工具契约/权限/SSE | MCP 服务 + 只读工具 |
| L3 检索与知识 | D3 | RAG/Embedding/混合检索/Rerank | 知识库问答 |
| L4 Agent 推理 | D4 | ReAct/Harness/HITL/证据链 | 诊断 Agent |
| L5 状态与执行 | D5 | Durable/Run State/记忆/Temporal | 可恢复执行 |
| L6 评测与安全 | D6 | 评测/红队/Guardrails/注入 | 评测集+红队报告 |
| L7 部署与进阶 | D7 | 部署/回滚/vLLM/多Agent/A2A | 上线+作品集 |

---

## L0 热身（D0，约 3 天）
- **目标**：看懂本词典所有名词，知道"这个项目要干什么"。
- **动作**：通读 `concepts-glossary.md` + `PLAN-v6.md` 的定位/架构。
- **产出**：能跟人讲清"为什么订单查询走工具、订单归因走 Agent"。

## L1 模型基础（D1，约 2-3 周）
> ⚠️ **分两层**：D1 只需要"够用层"；"深入层"是空闲时可选 Lab，**不阻塞 D1**。

**够用层（D1 必需）**：
- 动手：用已打通的火山模型(deepseek-v4-flash)做：普通 chat → 结构化输出(JSON) → Tool Calling。
- 理论：只懂"结构化输出/工具调用"的原理即可，不深挖 Transformer。
- 产出：LangChain4j 最小工程，调通模型 + 第一个业务指标工具。

**深入层（可选 Lab，有空再学）**：
- Transformer 直觉（Attention/位置编码）、Tokenization、Embedding、InstructGPT 对齐。
- 产出：Mini Transformer 笔记（学习用，不进生产）。

## L2 工具与协议（D2，约 3-4 周）
- **理论**：MCP 原理（Tools/Resources/Prompts、Streamable HTTP）；API 契约、幂等。
- **动手**：官方 MCP Java SDK 建 `my-xhs-ai-mcp`，暴露业务(L1)+观测(L2)只读工具；SSE。
- **产出**：MCP conformance + 契约测试 + 权限 L1/L2 落地。

## L3 检索与知识（D3，约 3-4 周）
- **理论**：RAG 论文、BM25、dense retrieval、hybrid、RRF、rerank。
- **动手**：指标字典/Runbook/故障手册 → 切片 → embedding(火山) → ES → 混合检索。
- **产出**：带 ACL + 引用回原文的口径问答；recall@k/MRR 基线。

## L4 Agent 推理（D4，约 4-5 周）
- **理论**：ReAct、Plan-and-Execute、Reflexion、ToT；Agent Harness；HITL。
- **动手**：手写 Harness（循环/预算/权限/事件），做业务归因 Agent + 排障证据链 Agent。
- **产出**：三个业务 + 三个排障场景端到端；证据/反证/不确定性输出。

## L5 状态与执行（D5，约 4 周）
- **理论**：Durable Execution、确定性 replay、记忆(MemGPT/Mem0)。
- **动手**：Run/Step 状态机、checkpoint、取消/重试/恢复；Worker kill 测试。
- **产出**：崩溃恢复演示 + HITL；Temporal PoC（若触发条件）。

## L6 评测与安全（D6，约 4-5 周）
- **理论**：评测指标、LLM-as-Judge、OWASP GenAI、红队。
- **动手**：JUnit+Promptfoo 三层评测；红队（注入/越权/PII）；Langfuse trace。
- **产出**：300+ 用例评测集 + 红队报告 + Dashboard。

## L7 部署与进阶（D7，约 3-4 周）
- **理论**：部署、回滚、容量、vLLM；多 Agent、A2A、Skills、Fine-tune。
- **动手**：Compose/K8s 部署、灰度回滚、压测。
- **产出**：上线 + 作品集（架构图/ADR/评测/故障报告/演示视频）。

---

## 概念 → 学习时机速查
| 你提到的概念 | 何时学 | 在哪用 |
|------|:--:|------|
| **Spec / SDD** | L0/D0 | 每个能力的规范 + 验收 |
| **TDD** | L1 起全程 | 确定性工具/权限测试先行 |
| **ReAct** | L4/D4 | 诊断 Agent 循环 |
| **A2A** | L7 选修 | 跨 Agent 协作（暂不用） |
| **MCP** | L2/D2 | 工具层 |
| **RAG** | L3/D3 | 知识库问答 |
| **Durable** | L5/D5 | 长任务恢复 |

---

## 学习方法建议（零基础）
1. **每阶段只学当阶段需要的**，不提前堆理论。
2. **边做边学**：理论读到能动手就停，用代码验证理解。
3. **用项目讲**：面试时用"订单为什么降"这个真实例子讲 ReAct/RAG/HITL。
4. **概念先查词典**：`concepts-glossary.md`，不懂再深挖论文。
5. **论文四步法**（PLAN v6）：阅读 → 最小复现 → 对照 my-xhs → ADR/实验报告。

---

## 论文/资料映射（想深学/面试时按需读）
> 对应"深入层"。**不必全读，D1-D4 只要"够用层"**。按阶段：

| 阶段 | 关键论文/资料 | 作用 |
|:--:|------|------|
| L1 | [Attention Is All You Need](https://arxiv.org/abs/1706.03762)、[InstructGPT](https://arxiv.org/abs/2203.02155) | Transformer/对齐直觉 |
| L2 | [MCP 规范](https://modelcontextprotocol.io/specification/latest) | 协议层 |
| L3 | [RAG](https://arxiv.org/abs/2005.11401)、BM25 | 检索增强 |
| L4 | [ReAct](https://arxiv.org/abs/2210.03629)、[Reflexion](https://arxiv.org/abs/2303.11366)、[ToT](https://arxiv.org/abs/2305.10601) | Agent 推理 |
| L5 | [MemGPT](https://arxiv.org/abs/2310.08560)、MemoryOS | 记忆/状态 |
| L6 | OWASP GenAI、AgentBench | 安全/评测 |
| L7 | LoRA/QLoRA、vLLM/PagedAttention | 微调/推理 |

---

## ⚠️ 时间现实性与精简路径
- **L0-L7 合计 ≈24-30 周并行**，假设**偏全职或每周高投入**（15-20h+）。
- **精简路径（推荐，若时间有限）**：只做每阶段的"够用层/动手"，跳过全部"深入层/论文"，**保交付不保理论深度**，约可省 1/3 时间。
- 面试竞争力主要来自**真实成果**（能跑的 Agent + 真实取舍），不是论文数量。
