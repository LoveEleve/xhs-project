# JD：企业级 AI Agent 开发工程师

> 来源：用户提供 | 公司：未署名 | 岗位：企业级 AI Agent 开发
> 保存日期：2026-08-10

---

## JD 原文（节选核心）

### 岗位职责
- 企业级 AI 应用：基于 LLM 构建智能助手、AI Agent、知识库问答、自动化工作流。
- Agent 系统架构：基于 **LangChain / LangGraph** 编排工作流；设计状态管理、任务规划、工具调用、多步骤推理。
- **基于状态机（State Machine）的 Agent 执行流程**：节点(Node)设计、状态(State)管理、条件路由(Conditional Routing)、Memory 机制、**HITL 人机协同**。
- LLM 工程化：集成 GPT / Claude / DeepSeek / 通义千问；Prompt 模板、上下文管理、RAG；优化成本/响应/质量。
- 知识库与 RAG：文档解析、Embedding 向量化、向量库检索、知识召回优化、多轮上下文。
- 工具生态：开发 Agent Tools 对接企业 API、数据库、SaaS、n8n。
- 工程化：服务接口设计、部署、日志监控、性能优化，Demo → 生产。

### 任职要求
- **必备：Python**（FastAPI/Flask、asyncio、REST API、数据处理）
- LLM 应用经验：Prompt Engineering、上下文管理；理解 Agent/RAG/Workflow/Tool Calling/Function Calling
- Agent 框架：LangChain / LangGraph / LlamaIndex / AutoGen / CrewAI；理解 Planning、**ReAct**、状态机编排、多 Agent、长流程任务
- 数据/基建：PostgreSQL/MySQL、Redis；向量库 **Milvus / Chroma / FAISS / pgvector**；Docker、Linux、API 网关

### 加分项
- 落地经验：AI 客服/知识库/销售助手/数据分析助手等
- **LangGraph 实战、Agent 状态机设计、RAG 调优、MCP 开发**、n8n/Dify/Coze 二次开发、AIGC(ComfyUI/SD/视频生成)
- **开源项目 / GitHub 个人 AI 项目优先**

---

## 技术栈信号提取（供选型印证）

| 信号 | 对应本项目 | 印证 |
|------|-----------|:--:|
| **Agent 状态机：Node/State/条件路由/Memory/HITL** | 我们的 Run 状态机 + HITL | ✅ 强印证 |
| Agent 工作流编排（LangChain/LangGraph） | LangChain4j（Java 版） | ✅ 同理念 |
| RAG + Embedding + 向量库 | D3（ES 向量） | ✅ |
| 工具生态（API/DB/SaaS） | D2 MCP 工具 | ✅ |
| **MCP 开发（加分）** | 我们就是做 MCP | ✅ 强印证 |
| ReAct / Planning / 多 Agent | 概念词典 | ✅ |
| 成本/响应/质量优化 | D6 | ✅ |
| GitHub 个人 AI 项目优先 | my-xhs-ai 即作品 | ✅ |
| **必备 Python**（FastAPI/asyncio/向量库全 Python 生态） | 本项目 Java | ⚠️ 强提醒 |

> **结论**：这份 JD 的**核心机制（状态机 Agent + Node/State/条件路由 + Memory + HITL + MCP + RAG）与我们的方案高度一致**，几乎是把我们的 PLAN 翻成了一份 JD。
> ⚠️ **但这是 7 份里 Python 要求最硬的一份**（必备 Python + 全 Python 技术栈）。信号：**AI 应用岗 Python 生态占绝对主流**。

---

## 汇总（JD1~JD7）—— 语言信号
| JD | 语言要求 |
|:--:|---------|
| JD1 Java AI Agent | Java（明确） |
| JD2 真实业务 AI | **Python** |
| JD3 自主 Agent 平台 | 未明（Python 生态工具） |
| JD4 AI Lab | Python/Go/**Java** |
| JD5 AI Agent 系统 | Python/Go/**Java** |
| JD6 电商 B 端 | 未明（LLM 调优） |
| JD7 企业级 Agent | **必备 Python** |

> **语言趋势**：7 份里 5 份是 Python 导向或 Python 生态；仅 JD1 明确 Java。**若 AI 就业是重要目标，Python 几乎是必补项；Java 只覆盖少部分岗位。** 本项目用 Java 练通概念完全成立（概念通用），但建议后续**用本项目为底、补一套 Python(LangGraph) 版本**作为就业加分。
