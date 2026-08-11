# JD：Agent 框架研发工程师

> 来源：用户提供 | 公司：未署名 | 岗位：Agent 框架研发（框架级）
> 要求：硕士学历 + C++/Python/Go | 保存：2026-08-10

---

## JD 原文（节选核心）

### 岗位职责
- 负责 **Agent 开发框架**设计与编码，搭建支持**多智能体系统（Multi-Agent System）协作、决策、工具调用**的底层能力。
- 研发框架核心模块（**任务调度、通信协议、工具集成**等），实现 Agent 自主规划、**CoT/ToT 推理**、上下文管理。
- 设计并落地标准化交互接口与协议（**MCP、A2A、FunctionCall**），打通 Agent 与外部系统、工具的对接。
- 整合主流 Agent 架构（多智能体、**ReAct/PlanAct/CodeAct**），搭建模块化、可插拔框架体系。
- 性能调优与稳定性：异步处理、资源调度提升吞吐量/并发。
- 配合算法团队将科研成果工程化落地。

### 岗位要求
- 硕士及以上；扎实系统设计、数据结构与算法。
- 精通主流 Agent 框架：**Eino、LangChain、AutoGPT、SuperAGI、CrewAI**。
- 熟练掌握 **C++/Python/Go** 任一。
- 大型复杂系统：分布式、消息队列、异步编程。
- 深入理解 **Multi-Agent System**，掌握 **MCP、A2A、FunctionCall** 交互协议。
- 设计并实现 **ReAct、PlanAct、CodeAct** 范式协议。

---

## 技术栈信号提取（供选型印证）

| 信号 | 对应本项目 | 印证 |
|------|-----------|:--:|
| **MCP + A2A + FunctionCall（三协议并列）** | MCP 我们做；A2A 在 L7 选修 | ✅ A2A 首次高调点名 |
| 多智能体系统（协作/决策/工具调用） | 预留 L7 | ✅ |
| ReAct / PlanAct / CodeAct | ReAct 有；PlanAct/CodeAct 需补 | ✅ |
| 任务调度 / 通信协议 / 工具集成 | Harness + MCP | ✅ |
| CoT / ToT 推理 | 概念词典 | ✅ |
| 模块化可插拔框架 | 我们的架构设计 | ✅ |
| 框架 Eino/LangChain/AutoGen/CrewAI | 理念同 LangChain4j | ✅（Python/Go 生态） |
| 分布式/消息队列/异步 | 16 服务经验 | ✅ |
| 语言 C++/Python/Go（无 Java） | Java | ⚠️ |

> **结论**：这是**框架研发级**（硕士 + C++/Python/Go），属进阶岗位。价值在于：**它首次把 A2A 与 MCP、FunctionCall 并列**——确认了 **A2A 是协议层的重要趋势**（我们目前只放 L7 选修，可考虑更早了解）。也印证 **ReAct/PlanAct/CodeAct** 范式是框架研发核心。
> ⚠️ 无 Java（C++/Python/Go）——此岗偏底层框架，非 Java 岗；但我们学的是范式（ReAct/PlanAct/CodeAct/多智能体/协议），概念可迁移。

---

## 汇总（JD1~JD15）
| 能力 | 份数 |
|------|:--:|
| Agent / 工具调用 / 记忆 | 15/15 |
| RAG | 15/15 |
| 真实业务落地 | 12/15 |
| 多智能体 | 11/15 |
| 可观测 | 7/15 |
| **MCP** | **5/15** |
| **A2A** | **1/15（首次高调）** |
| 微调 | 5/15 |
| Java 可行 | 7/15 |
