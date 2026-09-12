# RV09：技术必要性审查（以 RAG 争论为例）

> 日期：2026-09-12 ｜ 触发：用户要求"每个技术选择必须经过考虑，不许盲目堆技术"
> 背景：《RAG is dead》类文章在 2026 年形成共识——**死的是朴素 RAG（chunk→embed→top-k→硬塞），不是检索**；检索被降级为 Agent 工具箱里的一个组件（Agentic Retrieval / Hybrid / LLM Wiki）。
> 结论：**xhs-ai 不建默认 RAG 管线**。以"结构化知识卡 + 词法/元数据检索 + 代码导航 + Agent 自主检索"为 v1 主干；向量检索为**评测触发**的可选项，并预设止损条件。

---

## 1. 争论的准确理解（避免被标题带偏）

| 主张 | 出处/证据 | 对我们的含义 |
|---|---|---|
| 朴素 RAG 已死 | Turbopuffer：RAG≠向量搜索；"embed 一次、单次 top-k、塞进上下文"是稻草人 | 不走"切块+向量"默认管线 |
| 检索没死，是"升了层" | Forbes 2026-07：检索从流水线步骤变为上下文层（决定给模型看什么/顺序/成本） | 检索设计 = 上下文工程的一部分 |
| Agentic Retrieval 是新默认 | LangChain/LlamaIndex/Claude Code：模型驱动"改写→混合检索→多跳→证据校验"循环 | **我们已经在做**：M2.0 工具编排就是 Agentic Retrieval（dlq/MCP/ES 工具） |
| 代码类语料 grep+长上下文更强 | Claude Code 移除 embeddings 后效果更好；Boris Cherny"plain search 大幅胜出" | 代码定位走 **LSP/JGit/grep**，不用 embedding |
| LLM Wiki / 预编译知识 | Karpathy：~100 篇 wiki（40 万字）不用 fancy RAG，靠索引+摘要按需读；Pinecone Nexus 转"预编译可引用产物" | 我们的**三层知识卡 + catalog 索引**就是这条路 |
| 长上下文有天花板 | Chroma "context rot"：18 个模型全部随长度退化；Lost-in-the-Middle 中段掉 20-30 分；有效窗口≈标称 30-65% | 禁止"全量塞"；知识上下文预算 ≤8K |
| 何时该上检索/向量 | 语料 <20 万 token 直接塞；>100 万或 1000+ QPS 才值得管线；新鲜度/引用/合规才是 RAG 的护城河 | 我们语料小、QPS 低、引用要求高 → 词法+结构化优先 |

## 2. 我们的真实数据/查询特征（选型依据，非口号）

| 维度 | 事实 | 推论 |
|---|---|---|
| 高价值语料 | A 级知识卡：architecture 15 + business 8 + code-map 40 ≈ 63 个 YAML（每个 1-5KB）→ **<20 万 token** | 按业界决策线：**"直接读"优于建管线** |
| B 级语料 | business-analysis ~103 + vol-ai 13，治理后提炼成卡 | 卡片化后规模同样可控 |
| 查询类型 | "系统本体"（架构/链路/表/topic）、**精确标识符**（类名/方法名/表名）、历史故障 | 词法/元数据/代码导航命中率高于语义相似度 |
| 查询量 | 内部工具（ADR-6，~20 并发），非 1000+ QPS | 向量索引的规模收益不成立 |
| 新鲜度 | 知识随代码变更（低中频） | git 卡片 + 校验时间戳足够；无需小时级索引 |
| 合规 | 引用必须可回链（RQ01/PLAT） | 结构化卡片天然带 provenance；比 chunk 更可审计 |

## 3. 技术必要性审查表（存量 + 规划项全量）

动作标签：`保留`（已证明）｜`推迟`（触发条件未到）｜`拒绝`（不做）｜`改造`（换形态）

| 技术 | 现状态 | 必要性判定 | 替代/触发阈值/止损 |
|------|--------|-----------|-------------------|
| ES dense_vector 混合检索 | ADR-3 原定 v1 | **改造**：v1 只用 BM25+元数据；向量为可选实验 | 触发：EVAL 显示词法→答案准确率 <90% 且失败样本以"词汇不匹配"为主；止损：实验无 +5% 提升即删除 |
| Ark Embedding | 已验证 2048 维 | **推迟**（Key 保留） | 同上触发；禁止"先嵌了再说" |
| RRF 融合 / Rerank | 未建 | **推迟** | 仅在混合检索实验成立后按需加；单加组件必须带评测收益 |
| 独立向量库（Milvus/pgvector） | ADR-17 已分阶段 | **拒绝（v1）** | 阈值保持：>5 万 chunk 或 P95>300ms 再议 |
| 结构化知识卡（三层）+ catalog 索引 | 规划 | **保留（主干）** | 卡片是"预编译可引用产物"；catalog 供 Agent 渐进披露 |
| 代码导航（轻量解析+JGit） | ADR-18 v1 | **保留** | 先 grep/AST 轻量方案 |
| jdtls（LSP 完整版） | ADR-18 v1.1 | **推迟** | 触发：代码定位 EVAL 精确率 <90%；jdtls 内存/启动成本高 |
| Agentic Retrieval（工具循环） | M2.0 已实现雏形 | **保留（核心）** | 加约束：max_steps≤12（已有）、tool_timeout、budget、source_trace（已有审计） |
| MCP 工具（ES/Prom/Grafana） | 已接 3 个 | **保留** | 工具集预算 ≤8k token；Grafana 57 工具不注册 Agent 侧；>40 工具再加 tool_search |
| AgentScope 2.0 Harness | 已验证 | **保留** | 不再引入第二套 Agent 框架（Spring AI/LangChain4j 仅对照叙事） |
| Redis AgentState | 已落地 | **保留** | 会话态用 Redis；业务归档 MySQL（职责分离） |
| RocketMQ tools（DLQ） | 已落地 | **保留** | 真实业务需要；替代（Dashboard HTTP）因 CSRF/清洁度被否 |
| 多智能体协作 | 未做 | **拒绝（v1）** | JD18：先把单 Agent 工作流/评测做扎实；触发：单 Agent 复杂任务完成率 <70% |
| Langfuse/OTel 第三方观测 | 未做 | **推迟** | 先用自研 run 指标（token/耗时/工具成功率）；触发：排障需要 span 级对比 |
| Sidecar 扩展协议 v2 | 未做 | **拒绝（v1）** | 等 ≥2 个真实扩展需求出现再设计 |
| Temporal/工作流引擎 | 拒绝（ADR-4 继承） | **拒绝** | 无跨天长任务需求 |
| 微调（LoRA/SFT） | 未做 | **拒绝** | 诊断靠确定性工具+结构化输出；收益低于工具质量 |

## 4. v1 检索设计（替代原 RAG 计划）

```
用户问题
  → Agent（已有 ReAct 循环）
      ├─ 意图：本体知识 / 代码定位 / 历史故障 / 运行态诊断
      ├─ 知识工具 knowledge_search（BM25 + layer/tag 过滤，返回整卡而非 chunk）
      ├─ 代码工具 code_locate（grep/AST/JGit，方法级定位）
      ├─ catalog 索引（先读目录，再按需读卡片——渐进披露）
      └─ 运行态工具（MCP：ES 日志/PromQL；DLQ；MySQL）
  → 命中卡片/代码 **整篇注入**（small-to-big：小检索、大读取）
  → 上下文预算：知识注入 ≤8K token，按相关度排序 + provenance
  → 回答带引用（卡片路径/行号可回链）
```

评测（M3 出口门禁）：真实 KB 用例 ≥30 条（对齐 PLAT-05）；指标：命中率/引用有效性/拒答；**基线 = BM25+结构化**；向量实验必须相对基线有显著提升才保留。

## 5. 结论与计划修订

1. **保留**：结构化知识卡、BM25/元数据检索、代码导航、Agentic Retrieval、MCP 工具、Redis 状态。
2. **改造**：ADR-3 由"ES 混合检索为 v1"改为"结构化+BM25 为 v1，向量按评测触发"。
3. **推迟/拒绝**：向量库/Rerank/Langfuse/多智能体/jdtls/Sidecar（各自触发条件见 §3）。
4. 新增设计：`docs/design/12-retrieval-and-knowledge.md`；M3 验收改为"知识底座 + Agentic Retrieval + 评测报告"。
5. 简历与 JD 叙事同步改为"**评测驱动的检索选型（Agentic Retrieval / Hybrid on demand）**"——比"上了 RAG"更抗追问。

> 原则沉淀：**每个技术资产必须能回答三问——不引入它具体损失什么？替代方案是什么？什么数据出现时删除它？答不出就不引入。**
