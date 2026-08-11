# JD：企业级 AI Agent 工程师（专家级）

> 来源：用户提供 | 公司：未署名 | 岗位：企业级 AI Agent（专家级）
> 要求：本科；3 年+ 开发，其中 2 年+ AI Agent；Python + Go/Java/TS/Rust 任一 | 保存：2026-08-10

---

## JD 原文（节选核心）

### 岗位职责
- 企业级 AI Agent 应用、智能体底层基础能力的**架构设计、研发、生产落地**。
- 搭建 **Agent 工作流体系**：任务规划、状态管理、条件路由、工具调用、异常处理、中断恢复、人工审核。
- 基于 **LangGraph、Eino、OpenAI Agents SDK、AutoGen、CrewAI** 开发单/多智能体与复杂工作流。
- 搭建 **Agent 记忆体系**：短期记忆、长期记忆、案例记忆、任务状态、运行轨迹管理。
- 搭建 **RAG 知识库检索**：文档解析、文本分块、向量化、混合检索、结果重排、权限过滤、知识库更新。
- 落地**函数调用、结构化输出、JSON Schema、MCP** 工具调用与规范输出。
- 对接企业内部系统/API/数据库/搜索，搭建统一安全可控的 **Agent 工具集**。
- 搭建 **Agent 效果评估体系**：任务完成率、工具调用准确率、**幻觉率**、响应时延、Token 消耗、运行成本。
- 构建 **Agent 可观测与运维体系**：调用链路追踪、节点耗时统计、模型调用记录、工具调用日志、异常重试、运行审计。
- 解决生产**并发、超时、重试、幂等、限流、降级、故障恢复**线上稳定性。
- 调研前沿，技术选型、原型验证、产品落地。

### 任职要求
- 本科；**3 年+ 开发，至少 2 年大模型/AI Agent 实战**。
- **Python 为主，精通 Go/Java/TS/Rust 任一** → **Java 明确可投**。
- 微服务、REST、消息队列、缓存、数据库、分布式。
- 深刻理解模型能力边界：上下文窗口、Token 限制、幻觉、结构化输出、工具调用稳定性、成本。
- 精通**提示词工程、上下文工程**、输出约束规则。
- 至少一种框架：**LangGraph、Eino、OpenAI Agents SDK、Semantic Kernel、AutoGen、CrewAI**。
- 熟知范式：**ReAct、Plan-and-Execute、Reflection、Router、Supervisor-Worker、Agent as Tool、HITL(人在回路)、多智能体**。
- 容错可恢复工作流：状态管理、条件分支、循环终止、**断点存档 Checkpoint**、失败重试、人工审核。
- 函数调用、结构化输出、JSON Schema、**MCP**。
- **RAG 全链路**；任意一种向量/检索库（**Milvus、Elasticsearch、OpenSearch、pgvector、Qdrant、Weaviate、Chroma**）。
- **Agent Memory 落地**（会话/长期/任务状态/知识库/案例库）。
- **Agent 评测体系**：测试集、标准数据集、自动化回归、A/B。
- 排查模型/提示词/RAG/记忆/工具/工作流效果缺陷。
- 生产部署：**Docker、Linux、Git、CI/CD**。
- 加分：安全运营经验；GraphRAG/Neo4j/时序；**SFT、LoRA、蒸馏、Embedding 训练、重排微调**；**LangSmith、Langfuse、OpenTelemetry、Phoenix** 可观测评测工具；开源贡献。

> ⚠️ 备注：**仅做聊天机器人、简单知识库问答、开源 Demo 调试经验，不符合专家级岗位要求**。

---

## 技术栈信号提取（供选型印证）

| 信号 | 对应本项目 | 印证 |
|------|-----------|:--:|
| **Java 明确可投（Go/Java/TS/Rust 任一）** | Java | ✅ 强 |
| 工作流：任务规划/状态/条件路由/工具/异常/中断恢复/人工审核 | Harness 设计 | ✅✅ 1:1 |
| **记忆体系（短期/长期/案例/任务状态/运行轨迹）** | 记忆支柱 | ✅ 强 |
| RAG 全链路 + 混合检索 + 重排 + 权限过滤 | RAG 支柱 | ✅ |
| **函数调用/结构化输出/JSON Schema/MCP** | LangChain4j+MCP | ✅ |
| **效果评估体系（完成率/工具准确率/幻觉率/时延/Token/成本）** | D 阶段评估 | ✅✅ 高亮 |
| **可观测运维（链路追踪/节点耗时/模型/工具日志/审计）** | D1 可观测 | ✅✅ 高亮 |
| **生产稳定性（并发/超时/重试/幂等/限流/降级/恢复）** | D 阶段工程 | ✅✅ 高亮 |
| 范式：ReAct/Plan-Execute/Reflection/Router/Supervisor-Worker/Agent-as-Tool/HITL | L 学习 | ✅ |
| **Checkpoint 断点存档 / 中断恢复** | 需补入设计 | ⚠️ 补 |
| 向量库 ES 认可（列 Elasticsearch） | 我们用 ES | ✅ 印证 |
| **Langfuse/OpenTelemetry/Phoenix 可观测工具** | 需调研 | ⚠️ 补 |
| 微调 SFT/LoRA/蒸馏/Embedding/重排微调 | 学习重点 | ✅ |

---

## 结论（本份为最全面专家级）

1. **本份 JD 与 my-xhs-ai 的 D 阶段设计几乎 1:1 对齐**——工作流、记忆、RAG、工具集、评估体系、可观测运维、生产稳定性，全部命中我们的规划。
2. **Java 明确可投**，就业面再 +1 → **10/17 Java 可投**（本份为第 18 份）。
3. **四个高亮新点需补进设计/词典**：
   - **Checkpoint 断点存档 / 中断恢复**（工作流恢复能力）
   - **效果评估体系指标**（完成率/工具准确率/幻觉率/时延/Token/成本）——把"评估"升为一等公民
   - **Langfuse / Phoenix / OpenTelemetry** LLM 可观测工具调研
   - **混合检索（向量 + 关键词 BM25）** 与 **权限过滤**、**知识库更新**
4. **再次强调"真实业务落地"是专家级门槛**——"仅聊天机器人/简单 RAG/开源 Demo 不符合要求"，直接印证我们走 my-xhs 真实业务 3 能力面是对的。
