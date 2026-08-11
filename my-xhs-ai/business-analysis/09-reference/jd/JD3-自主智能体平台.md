# JD：自主智能体平台（OpenClaw/Hermes 类）

> 来源：用户提供 | 公司：未署名 | 岗位：AI Agent 核心架构研发
> 保存日期：2026-08-10

---

## JD 原文

### 业务背景
我们正在打造类似 OpenClaw/ Hermes Agent 的自主智能体平台，面向个人与企业提供主动执行、多智能体协同、长期记忆、工作流自动化的 AI 原生产品。

### 岗位职责
- **核心架构研发**：设计与开发类 OpenClaw/ Hermes 的 AI Agent 系统，实现任务规划、工具调用、记忆管理、多智能体协同、自主执行全链路能力。
- **多智能体编排**：搭建 Agent 调度与协作框架，支持多角色智能体分工协作、任务分发、结果聚合与决策闭环。
- **大模型集成与优化**：对接 GPT、Claude、Gemini、Llama 等大模型，实现 Function Calling、Prompt 工程、RAG、上下文压缩、幻觉抑制。
- **主动智能体能力**：开发主动触发、定时任务、心跳机制、主动提醒、自主迭代等主动执行能力，打造"不用催、自己干"的智能体。
- **工程化落地**：负责 Agent 服务的容器化部署、监控告警、性能优化、高可用架构，支撑生产级稳定运行。
- **技术迭代**：跟进 ReAct, CoT, ToT, Dify, LangGraph, AutoGen, CrewAI 等前沿技术，持续迭代产品竞争力。

---

## 技术栈信号提取（供选型印证）

| 信号 | 对应本项目 | 印证 |
|------|-----------|:--:|
| 任务规划 / 工具调用 / 记忆管理 / 自主执行 | D4 Harness + D2 工具 + D5 记忆 | ✅ |
| **多智能体编排**（调度/协作/任务分发/聚合） | 预留 Multi-Agent（L7） | ⚠️ 本项目 V1 单 Agent |
| 多模型对接（GPT/Claude/Gemini/Llama） | LiteLLM（多 Provider 时） | ✅ 预留 |
| **Function Calling / Prompt / RAG / 上下文压缩 / 幻觉抑制** | 概念词典 + D3 + D6 | ✅ |
| **主动执行**（定时/心跳/主动提醒/自主迭代） | Durable Workflow / XXL-Job | ✅ 相关 |
| 容器化 / 监控告警 / 高可用 | D7 部署 | ✅ |
| **ReAct / CoT / ToT / LangGraph / AutoGen / CrewAI** | 概念词典(ReAct/ToT) | ✅ 概念覆盖 |

> **结论**：这是**最完整的一份**，几乎覆盖我们概念词典的所有核心词（ReAct/CoT/ToT/RAG/Function Calling/幻觉抑制/多智能体/自主执行）。它印证了我们的**学习轨方向正确**，也提示：**多智能体编排 + 自主执行是市场强需求**（本项目 V1 单 Agent 是保守正确，但学习轨 L7 应重视）。
> ⚠️ 该岗位偏 Python 生态（Dify/LangGraph/AutoGen/CrewAI 均 Python）。再次印证"若以就业为重，Java 需与 Python 生态互补"。

---

## 汇总（JD1+JD2+JD3）
| 市场共需能力 | 本项目覆盖 | 备注 |
|------|:--:|------|
| Agent / Tool Calling / 记忆 | ✅ D2/D4/D5 | 三份都有 |
| RAG | ✅ D3 | 三份都有 |
| 评测 / 日志 / 监控 | ✅ D6+可观测 | 三份都有 |
| 真实业务落地 | ✅ 核心定位 | — |
| 多智能体编排 | 🔲 预留 L7 | 市场强需，V1 单 Agent |
| 主动/自主执行 | 🔲 预留 | 相关 Durable/Job |
| 语言 | ⚠️ Java 为主 | 市场 Python/Java 都有 |
