# my-xhs-ai 最终架构图（2026-08-10 定稿）

> 结合：PLAN v6 §3 + DAD + ADR-001~006 + 部署包实况（2026-08-13）。
> 阅读：先看总图，再分块看。所有数字/状态/口径可追溯。

---

## 一、总览（一图到底）

```
                                用户 / 系统
      ┌──────────────────┬───────────────────┬──────────────────┐
      │  运营             │  技术支持/SRE       │  研发 / 其他系统     │
      │ (业务诊断 A)      │ (技术排障 B)       │ (API 调用)         │
      └────────┬─────────┴─────────┬─────────┴─────────┬──────────┘
               │ 浏览器加载前端        │ 浏览器加载前端      │ REST/SSE
               ▼                     ▼                   │
   ┌─────────────────────────────┐                    │
   │   my-xhs-ai-ui  19022       │  ← 面向用户的门面    │
   │   React 前端(静态+SSE客户端) │    (用户直接操作它)   │
   └─────────────┬───────────────┘                    │
                 │ ① 加载静态资源（直连UI/CDN）         │
                 │ ② API/SSE 请求 → /api/ai/**        │
                 ▼                                    ▼
   ┌──────────────────────────────────────────────────────┐
   │              my-xhs-gateway  19080（统一 API 入口）     │
   │      认证(JWT) · 角色 · 限流 · 路由 /api/** → 各服务     │
   │      路由 /api/ai/** → my-xhs-ai（含 SSE 转发）         │
   └──────────────────────────┬───────────────────────────┘
                              ▼
   ┌───────────────────────────────────────────────────────┐
   │                    my-xhs-ai  19020（核心，自研）        │
   │                                                       │
   │  IntentRouter ── 指标 / 观测 / RAG / Agent / Workflow  │
   │   ├─ DeterministicPath（固定查询→工具直取，带来源）       │
   │   ├─ RAGService（知识库问答，ACL+引用）                 │
   │   └─ AgentPath ──► AgentHarness                        │
   │        LoopCtrl(预算/循环检测) · StepEngine(plan/act)   │
   │        EvidenceChain(证据/反证) · HitlGate(L3) · Policy │
   │                                                       │
   │  MemorySystem（三层，MemoryOS 启发，D5 落地）            │
   │   短期=Redis工作记忆 · 中期=ES(heat_score) · 长期=MySQL  │
   │   MemoryRetriever(三层降级) · MemoryConsolidator(LRU/   │
   │   冲突/置信度)                                          │
   │  RunManager(Run/Step状态机+Checkpoint) · Conversation  │
   │  ModelProvider(LangChain4j) · Guardrails · Audit ·    │
   │  Trace · Cost                                          │
   └──────────────────────┬────────────────────────────────┘
                          │ MCP client（内部调用，不对用户/UI 暴露）
                          ▼
   ┌─────────────────────────────────────────────────────────┐
   │              my-xhs-ai-mcp  19021（工具层，内部）         │
   │  business-mcp (L1) ｜ observability-mcp (L2) ｜ knowledge │
   └──────────┬────────────────────────────┬─────────────────┘
              │ Feign/HTTP                 │ SQL/PromQL/ES DSL(trace)
              ▼                            ▼
   ┌─────────────────────────────────────────────────────────────┐
   │               my-xhs 业务与数据（16 服务）                     │
   │  user · content · home · search · order · payment · cart    │
   │  inventory · coupon · analytics · notification · im · ...   │
   │  ── 权威存储 ──                                              │
   │  MySQL 3306(主)⇄3307(从 GTID) · Redis · RocketMQ · ES 19200  │
   └───────────────┬───────────────────────────┬─────────────────┘
                   │ 采集                        │ 追踪
                   ▼                            ▼
   ┌──────────────────────────────────────────────────────────────┐
   │          可观测栈（云主机已部署，2026-08-13 实况）              │
   │  Prometheus 19090 ──► VictoriaMetrics(remote_write 8428)     │
   │   exporters: mysql 9104 · mysql-slave 9105(复制延迟)          │
   │              redis 9151 · canal 11112 · es 9114 · node 9100   │
   │              rocketmq(textfile脚本→node) · skywalking 1234    │
   │  Grafana(9看板) · AlertManager 19093 · SkyWalking OAP 11800   │
   │  Logstash 15044(微服务TCP) ─► ES 19200 ─► Kibana             │
   └──────────────────────────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────────────┐
   │   模型：火山方舟（ARK_API_KEY 环境变量，绝不落库）               │
   │   日常查询: deepseek-v4-flash | 归因: 推理模型(D4 再定)        │
   └──────────────────────────────────────────────────────────────┘
```

> **对外边界澄清（本版修正）**：
> - **面向用户** = `my-xhs-ai-ui`（浏览器加载的前端，用户直接操作）。
> - **统一 API 入口** = `my-xhs-gateway`（认证/角色/限流；`/api/ai/**` 路由到 my-xhs-ai；UI 与研发的 API/SSE 请求都走它）。
> - **内部工具层** = `my-xhs-ai-mcp`（只被 my-xhs-ai 调用，不对 UI/用户暴露）。

---

## 二、分层视图（部署边界）

| 层 | 组成 | 端口 | 备注 |
|----|------|:--:|------|
| 用户层 | **my-xhs-ai-ui**（React 前端，浏览器加载）| 19022 | 面向用户的门面，SSE 流式展示 |
| 接入 | **my-xhs-gateway**（现有）| 19080 | 统一 API 入口：认证/角色/限流，路由 `/api/ai/**` |
| AI 核心 | my-xhs-ai | 19020 | API/Router/Agent/RAG/Run/SSE |
| 工具层 | my-xhs-ai-mcp | 19021 | 固定只读工具 L1/L2（内部，不对外）|
| 中间件 | MySQL/Redis/RocketMQ/ES/Nacos/Canal/xxl-job | 云主机 | 部署包已就绪 |
| 可观测 | Prometheus/exporters/Grafana/VM/AlertManager/SkyWalking/Logstash | 云主机 | 已部署 |

> 微服务(15) 与 中间件(25容器) 分机部署；日志经 LogstashTcpSocketAppender 直推 15044。

---

## 三、数据与状态分离（四类，严格分开）

| 状态 | 权威存储 | 备注 |
|------|---------|------|
| 业务事实 | 原服务/MySQL/Redis | Agent 不持有 |
| Run/Step 状态 | Run Store（MySQL）+ Checkpoint | D5 |
| Conversation | Redis + 会话表 | 会话上下文 |
| Long-term Memory | **三层（MemoryOS 启发）**：短期=Redis(工作记忆+TTL，类比CPU缓存) · 中期=ES(heat_score 热度，类比RAM) · 长期=MySQL+ES(偏好/经验，类比磁盘) | D5 按需引入；见下 |

### 3.1 MemorySystem（D5，MemoryOS 三层记忆——原规划 Phase-04）
> 来源：`../../docs/phase-04-agent-memory/README.md`（MemoryOS EMNLP 2025：OS 级记忆管理）。
> **目的**：让 Agent 记住用户偏好/历史经验；与 Run State / Conversation / 业务事实严格分离（不是业务事实源）。
- **三层**：短期 Redis Hash+TTL → 中期 ES+heat_score（高频自动"加热"）→ 长期 MySQL+ES（持久）。
- **四大模块**：Storage(存入) → Updating(层级流转) → Retrieval(三层降级检索) → Consolidation(自动压缩/冲突/置信度)。
- **Java 类草案**：`WorkingMemoryStore`(Redis) / `MidTermMemoryManager`(ES+heat) / `LongTermMemoryStore`(MySQL) / `MemoryHeatManager`(LRU/LFU) / `MemoryRetriever` / `MemoryConsolidator`。
- **进入条件**：仅当评测证明长期记忆有增益才实现可插拔 Memory Adapter（对比 Mem0/Zep/Letta，不直接当生产架构）。

---

## 四、横切能力（从 D0 贯穿）

```
Policy(deny-by-default) · Guardrails(注入/PII/内容) · Audit(不可变)
Trace(OTel+Langfuse) · Eval(三层评测集) · Cost(预算) · 版本化(模型/工具/索引/策略)
```

---

## 五、关键不变量（红线）

1. 业务数字 = 确定性工具产生，带口径/时间窗/来源；Agent 不编造。
2. 禁止任意 SQL/PromQL/ES DSL；V1 无写工具；L3 一律 HITL。
3. 四类状态分离；Step=checkpoint 粒度，崩溃可恢复。
4. 全链路 trace（runId=stepId）+ 审计；trace/日志无密钥与未脱敏 PII。
5. 韧性 14 项清单（§6.1）逐项过评测。
