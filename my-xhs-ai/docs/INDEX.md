# docs/ 前序规划索引（17 Phase）

> 版本：2026-08-10 | 用途：`docs/phase-00~16` 17 个 phase 的**学习轨素材库**索引，映射到交付轨 D0-D7 与学习轨 L0-L7。
> 权威总规划：`PLAN.md`（v5，含迁移表 §13）。当前架构/审计/决策：`../business-analysis/`。
> ⚠️ **不丢弃**：这些是学习素材与参考实现，已按 `PLAN.md` §13 映射，不作为实施顺序本身。

---

## 一、总览表（phase → D/L 映射）

| Phase | 标题 | 对应交付 | 对应学习 | 文档数 | 一句话 |
|:--:|------|:--:|:--:|:--:|------|
| 0 | 系统架构蓝图 | D0 | — | — | 架构基线 |
| 1 | Python + ML 基础 | —（L1 Lab）| L1 | 5 | 前置语言/ML 基础 |
| 2 | LLM 原理 + Prompt 工程 | D1 | L1 | 9 | Token/Attention/Transformer/结构化输出/注入 |
| 3 | RAG + 上下文工程 | D3 | L2 | 9 | 解析/分块/向量/混合检索/Rerank |
| 4 | Agent 记忆系统 | D5 | L4 | 6 | **MemoryOS 三层记忆**（OSMem）|
| 5 | Agent 架构全貌 | D4/D5 | L3 | 14 | 控制平面/分布式运行时/**AgentScope 2.0 深学** |
| 6 | MCP 协议 + 工具生态 | D2 | L5 | 8 | MCP Server 设计/生态 |
| 7 | 多 Agent 协作 + A2A | —（L7）| L7 选修 | 5 | 多Agent模式/A2A/HITL |
| 8 | Agent Skills 技能工程 | D2 后（L5）| L5 | 6 | SKILL.md/生命周期/注册中心 |
| 9 | 评测体系 | D0 贯穿 + D6 | L6 | 7 | 5 维评测/金字塔/黄金集/流水线 |
| 10 | AgentOps + 可观测 | D1 贯穿 + D6 | L6 | 6 | 五层架构/Langfuse/指标/成本/告警 |
| 11 | AI 安全 + Guardrails | D0 贯穿 + D6 | L6 | 6 | OWASP/注入/Guardrails/PII/审计 |
| 12 | 部署 + 推理 + 沙箱 | D7 | L6 | 7 | 本地模型/量化/推理优化/K8s/沙箱 |
| 13 | 企业治理 + 合规 | D0/D6/D7 贯穿 | L6 | 6 | EU AI Act/审计/权限/风险/数据治理 |
| 14 | 微调 + 领域适配 | —（L7）| L7 条件选修 | 5 | RAG vs 微调/LoRA/数据工程 |
| 15 | 系统集成验收 | 每阶段 + D7 | — | 6 | E2E/压测/Demo/手册/报告 |
| 16 | AI 编程工具效能 | 全程并行 | — | 4 | **SDD/Claude Code/Cursor/pi**（开发效率）|

> 映射依据：`PLAN.md` §13 迁移表。

---

## 二、逐 Phase 文档清单

### Phase 0：系统架构蓝图 → D0
- 架构基线（C4/边界），迁移至 business-analysis D0/DAD。

### Phase 1：Python + ML 基础 → L1 Lab（不阻塞 D1）
- 01 python-basics / 02 numpy-pandas / 03 ml-concepts / 04 neural-network / 05 huggingface

### Phase 2：LLM 原理 + Prompt 工程 → D1 + L1
- 01 tokenization / 02 embedding / 03 attention / 04 transformer / 05 kv-cache / 06 decoding / 07 prompt-system / 08 structured-output / 09 prompt-security

### Phase 3：RAG + 上下文工程 → D3 + L2
- 01 document-parsing / 02 chunking-strategy / 03 embedding-compare / 04 vector-store(ES dense_vector) / 05 retrieval-strategy / 06 query-optimization / 07 context-engineering / 08 rag-evaluation / 09 myxhs-knowledge

### Phase 4：Agent 记忆系统 → D5 + L4（★OSMem）
- 01 memory-evolution / 02 memoryos-deep-dive（MemoryOS EMNLP 2025）/ 03 tool-comparison(Mem0/Zep/Letta) / 04 java-memory-impl(Redis+ES+MySQL+LRU) / 05 consolidation / 06 benchmark(LoCoMo)

### Phase 5：Agent 架构全貌 → D4/D5 + L3（★AgentScope 深学）
- 5-A 理论：01 control-plane(OpenAI Symphony) / 02 distributed-runtime(Google Agent Executor) / 03 loop-comparison / 04 graph-orchestration(LangGraph) / 05 myxhs-runtime / 06 react-paper / 07 reflexion-paper / 08 harness-survey-paper
- 5-B AgentScope 2.0 深学：09 harness-architecture / 10 memory-layer / 11 skill-lifecycle / 12 permission-system / 13 plan-mode-events / 14 agentscope-vs-langchain4j

### Phase 6：MCP 协议 + 工具生态 → D2 + L5
- 01 mcp-protocol / 02 mcp-server-design / 03 order-user-payment-mcp / 04 inventory-content-product-mcp / 05 coupon-analytics-search-mcp / 06 log-mcp / 07 mcp-ecosystem / 08 agent-mcp-integration
- ⚠️ 注意：PLAN 决策为**一个聚合 MCP**（`my-xhs-ai-mcp`），不是 9 个 Server；本 phase 的分组文档作为**逻辑工具组**参考。

### Phase 7：多 Agent 协作 + A2A → L7 高级选修
- 01 multi-agent-patterns / 02 a2a-protocol / 03 meta-planner / 04 parallel-execution / 05 hitl

### Phase 8：Agent Skills 技能工程 → L5
- 01 prompt-to-skill / 02 anthropic-repo-analysis / 03 skill-spec-design / 04 skill-lifecycle / 05 myxhs-skill-registry(Nacos) / 06 skill-ecosystem

### Phase 9：评测体系 → D0 贯穿 + D6
- 01 constraint-decay / 02 5d-framework / 03 testing-pyramid / 04 golden-dataset / 05 automation-pipeline / 06 production-scorecard / 07 tool-comparison(DeepEval/RAGAS/Promptfoo/Langfuse)

### Phase 10：AgentOps + 可观测 → D1 贯穿 + D6
- 01 agentops-architecture / 02 litellm-deep-dive / 03 langfuse-deep-dive / 04 agent-metrics / 05 cost-dashboard / 06 alerting

### Phase 11：AI 安全 + Guardrails → D0 贯穿 + D6
- 01 owasp-llm-top10 / 02 prompt-injection / 03 guardrails-tools / 04 pii-masking / 05 tool-security / 06 audit-logging

### Phase 12：部署 + 推理 + 沙箱 → D7
- 01 local-model / 02 quantization / 03 inference-optimization / 04 docker-compose / 05 k8s-deployment / 06 sandbox / 07 canary-rollout

### Phase 13：企业治理 + 合规 → D0/D6/D7 贯穿
- 01 eu-ai-act / 02 audit-trail / 03 permission-model / 04 risk-classification / 05 data-governance / 06 compliance-checklist

### Phase 14：微调 + 领域适配 → L7 条件选修
- 01 rag-vs-finetune / 02 lora-principle / 03 data-engineering / 04 finetune-practice / 05 evaluation

### Phase 15：系统集成验收 → 每阶段 + D7 最终
- 01 e2e-scenarios / 02 stress-test / 03 demo-script / 04 user-guide / 05 ops-guide / 06 final-report

### Phase 16：AI 编程工具效能 → 全程并行（★pi）
- 01 sdd-methodology / 02 claude-code-practice / 03 cursor-practice / 04 code-review-checklist
- ★ **pi agent**（`earendil-works/pi`）作为**开发用编码 Agent**加入本轨（对标 Claude Code/Cursor）；深读见 `../business-analysis/09-reference/pi-reference.md` + `../business-analysis/09-reference/source-study/README.md`。

---

## 三、关键概念速查（前序规划里强调的）

| 概念 | 出处 | 一句话 |
|------|------|--------|
| **OSMem / MemoryOS** | Phase-4 | OS 级记忆管理：短期=CPU缓存(Redis)、中期=RAM(ES+heat)、长期=磁盘(MySQL)；Flush→Consolidation→Compaction |
| **Agent = Model + Harness** | Phase-5 | 华为 Ping Guo 公式；Harness=循环/预算/权限/HITL/上下文压缩 |
| **AgentScope 双层** | Phase-5-B | ReActAgent（推理引擎）+ HarnessAgent（工程化层） |
| **权限三态** | Phase-5-B/11 | 允许/审批/拒绝 + HITL 内生 |
| **28 类型化事件** | Phase-5-B | start→delta→end 三段式，SSE 推送 |
| **Loop vs Graph** | Phase-5-A | 简单任务用循环，复杂多步+分支用图 |
| **SDD** | Phase-16 | Spec 驱动开发：需求→Spec→AI生成→Review→测试 |
| **Constraint Decay** | Phase-9 | 复杂约束下 Agent 退化现象 |
| **三层评测金字塔** | Phase-9 | Static→Unit→Integration→E2E |
| **6 维权限模型** | Phase-11/13 | 用户/角色/工具/数据/时段/审批 |

---

## 四、与 business-analysis/ 的分工

| 目录 | 角色 |
|------|------|
| `docs/`（本目录）| 学习轨素材库：17 phase 学什么/顺序（L0-L7 的教材）|
| `docs/PLAN.md` | v5 总规划（含迁移表、面试题、作品集、技术雷达）|
| `../business-analysis/` | 当前权威：PLAN-v6、DAD、D0 审计/方案、ADR、架构图、JD 市场、源码研究 |
| 桥接 | `../business-analysis/README.md`（分工说明）、`../business-analysis/tech/architecture-diagram.md`（已接入 MemoryOS/pi）|
