# JD 命中矩阵 v2（my-xhs-ai → JD1~JD18）

> 日期：2026-08-17 | 用途：投递地图——先按语言过滤，再按能力命中，最后给每个缺口打动作标签。
> 口径说明：频率数字来自 `09-reference/jd/README.md`（JD1~JD18 存量总结），存在口径不一致（"可观测/可追溯/质量管控 ~11/18"为合并类、"效果评估 ~3/18"为单列）；**低频率 ≠ 低价值**（见 §四）。

---

## 一、先按语言过滤（第一步，避免投错）

语言要求逐份核实（来源：各 JD 原文头部/岗位要求）：

| 梯队 | JD | 语言要求 | 判定 |
|------|----|---------|------|
| **T1 明确可投（Java 主/可）** | JD1 JD4 JD5 JD6 JD8 JD9 JD10 JD14 JD16 | Java / Java 为主 / 任一 | ✅ 9 份 |
| **T2 可投但 Python 为主** | JD13 JD17 JD18 | Python + Go/Java/Rust 任一 | ⚠️ 需补 Python 叙事 |
| **T3 Python 生态/必备（不主投）** | JD2 JD3 JD7 JD11 | Python / Python 生态 / 必备 Python | ❌ Java 不直接可投 |
| **T4 无 Java** | JD15 | C++/Python/Go + 硕士 | ❌ 不投 |
| **未核实** | JD12 | 原文无语言字段 | ⚠️ 投前核实 |

> 关键修正：**JD7 是"必备 Python"**，不可放进 Java 可投梯队（v1 曾误放）。
> **JD18 是"Python 为主 + Java 可投"**——不是纯 Java 岗，投前需准备 Python 补位叙事。

---

## 二、能力 × 命中矩阵（带动作标签）

动作标签：`补件`=值得补 / `讲法`=用话术讲成克制 / `放弃`=不为 JD 投入

| 能力项 | JD 频率 | 本项目对应 | 命中 | 动作 |
|--------|:--:|-----------|:--:|:--:|
| Agent 工作流（状态/路由/工具/异常/恢复/审核） | 18/18 | `IntentRouter` + `AgentHarness` + `RunManager` | ✅ | 讲法 |
| 工具调用 + MCP | 18/18（MCP 6/18） | `ToolRegistry` + MCP 14 工具 | ✅ | 讲法 |
| 真实业务落地 | 13/18 | 订单/支付/内容 + 运维 5 案例 | ✅ | 讲法（核心差异） |
| 多智能体协作 | 13/18 | `AgentDispatcher` + BUSINESS/OPS/FULL | ✅ | 讲法 |
| 记忆/会话（短期/长期/案例） | 18/18 | `ConversationService` 规则摘要 + `ai_conversation` | ⚠️ 部分 | **补件**（长期 Memory） |
| RAG 全链路 | 17/18 | ES RAG + 混合检索 + 口径问答 | ✅ | 讲法 |
| 可观测/审计 | ~11/18 | RunMetrics + `ai_step` + MCP 审计 | ⚠️ 部分 | **补件**（Langfuse/OTel PoC） |
| 效果评估体系 | ~3/18（JD18 高亮） | `EvalRunner` + `EvalGate` + `EvalJudge` | ✅ | 讲法 |
| 稳定性（并发/超时/重试/幂等/恢复） | 高频 | `AgentBudget` + `JdbcRunStore` + checkpoint | ✅ | 讲法 |
| 安全边界（HITL/越权/注入） | 中频 | `PolicyGuard` + HITL + deny-by-default | ✅ | 讲法 |
| Checkpoint 断点存档/恢复 | JD18 | `JdbcRunStore` checkpoint + resume | ✅ | 讲法 |
| 向量数据库 | ~6/18 | ES knn | ⚠️ 分 JD（见 §三） | 讲法 |
| 微调（LoRA/SFT/RL/蒸馏） | 6/18 | 无 | ❌ | 学习（不补件） |
| Langfuse/OTel/Phoenix | JD18 加分 | 无 | ❌ | **补件** |
| A2A 协议 | 1/18（JD15 高信号） | 无（同进程共享 Harness） | ❌ | 讲法（克制） |
| 范式（ReAct/PlanAct/CodeAct/Reflection） | JD15/18 | ReAct 已实现，CodeAct 刻意不用 | ⚠️ 部分 | 讲法 |
| 多租户/RBAC | JD18 加分 | 单组织（ADR-006） | ❌ | 讲法（已决策） |

---

## 三、向量库：拆成"对哪份 JD"

| JD | 点名向量库 | ES 是否命中 |
|----|-----------|:--:|
| JD18 | Milvus、**Elasticsearch**、OpenSearch、pgvector、Qdrant、Weaviate、Chroma | ✅ 在清单内 |
| JD17 | Milvus / FAISS / Chroma / PGVector | ⚠️ 未点名 ES |
| 其余 | 未明确 | ✅ 理念一致（embedding 检索） |

> 结论：ES knn 对 JD18 **命中**，对 JD17 需要"可讲成理念一致 + Milvus 作可选第二实现"。

---

## 四、低频率但高信号（频率 ≠ 重要度）

| 项 | 频率 | 为什么高信号 | 对策 |
|----|:--:|-----------|------|
| 效果评估体系 | ~3/18 | JD18 明确高亮，"评估"是一等公民 | ✅ 已实现，重点讲 |
| A2A | 1/18 | JD15 把它与 MCP/FunctionCall 三协议并列，协议层趋势 | 讲法：理解但克制不引入 |
| 向量库 | ~6/18 | JD17 单点强化，RAG 全链路要求 | 讲法：ES 已够，Milvus 作对照 |
| 微调 | 6/18 | 学习重点（README 已升级） | 学习，不作为本项目补件 |

---

## 五、补位话术（缺口 → 怎么讲）

| 缺口 | 补位说法 |
|------|---------|
| 长期 Memory 未做 | "Run/Conversation/RAG 已分离，会话内规则摘要已做；独立长期 Memory 是明确下一步，我清楚四态边界" |
| 无 Langfuse/OTel | "先自研 run 级指标（token/成本/状态已落库），验证价值后再 PoC Langfuse，避免过早引入重依赖" |
| 向量库用 ES | "ES knn 已够用，且 JD18 明确认可 ES；Milvus 可作可选第二实现，理念一致" |
| 无微调 | "诊断 Agent 走确定性工具 + 结构化输出，flash 已够；微调是学习重点而非本项目刚需" |
| 无 A2A | "当前 Agent 同进程共享 Harness，无跨系统通信需求；A2A 是协议趋势，已理解但刻意不引入" |
| 无 CodeAct | "企业诊断安全优先，不允许模型获得任意代码/SQL 执行能力——这是红线，不是能力缺失" |
| 无多租户/RBAC | "已做 ADR-006 决策：单组织场景，多租户是加分项非必需，用户级认证先行" |
| Python 缺（T2/T3 岗） | "Java 已练通概念；后续可用本项目为底补一套 Python/LangGraph 对照版作就业加分" |

---

## 六、投递结论（语言准确版）

- **当前直接投（9 份）**：JD1 JD4 JD5 JD6 JD8 JD9 JD10 JD14 JD16 —— Java 明确可投
- **补 Python 后投（3 份）**：JD13 JD17 JD18 —— Python 为主，Java 副
- **不主投（5 份）**：JD2 JD3 JD7 JD11 JD15 —— Python 生态/必备/无 Java
- **投前核实（1 份）**：JD12

> 核心定位：强在"真实业务 + 工程闭环 + 可评测 + Java 落地"；弱在"微调 / Langfuse / 长期 Memory / A2A / 专用向量库"，多数可讲成克制。

---

## 七、行动清单（与收官任务闭环）

| 缺口 | 动作 | 关联任务 |
|------|------|---------|
| 长期 Memory | 补件 | 后续独立里程碑（非本周） |
| Langfuse/OTel | 补件 | T14 外部方案对照后决定 |
| Python 对照 PoC | 补件 | T14 后（提升 T2/T3 投递面） |
| 证据归档（eval 报告） | 补件 | T1/T4（报告落 docs/reports） |
| 其余缺口 | 讲法 | T6 九问 + 本文 §五 |
