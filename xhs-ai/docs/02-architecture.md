# 02 · 架构设计（v0.2）

> 状态：P3 评审中｜基于 AgentScope 2.0 Java（HarnessAgent）+ Spring Boot 3
> 变更：折叠 ADOPT-1~9（R03）、DELTA-1~8（RV02）、G1-G20（RV01/RV03）；专项设计见 `docs/design/`

## 1. 总体架构

```
┌──────────────────────────────── xhs-ai (port 19020) ─────────────────────────────────┐
│ API 层        ChatController(SSE) / SessionController / ApprovalController / Admin    │
│ Agent 层      HarnessAgent ── Middleware: Tracing / Audit / Permission / Cost / Guard │
│               Skills(GitSkillRepository)  Compaction  Plan(v1.5)  SubAgent(v1.5)      │
│ 执行层        PolicyEngine(有序规则) │ ApprovalService(状态机) │ ToolRunner(契约)        │
│ Tools 层      health/log/metric/trace/dlq/xxljob · code(LSP+Git) · knowledge · mcp    │
│ Knowledge 层  入库管道 → 分块 → Ark embedding → ES(dense_vector+BM25)  │ LSP(jdtls)   │
│ Memory 层     Redis(Sentinel) DistributedStore: AgentState + Remote workspace 文件    │
│ Query 层      会话/审计统一查询（模型工具 + 导出）                                       │
│ Observability Capture(metadata/sanitized/full) + OTLP→Langfuse + 成本/工具指标        │
│ Store 层      my_xhs_ai: session/message/approval/audit/grant/knowledge/eval/feedback │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. 关键流程与契约

### 2.1 对话（流式）
1. `POST /api/ai/chat/stream {sessionId, message}`（JWT→userId）
2. `(userId, sessionId)` 恢复 AgentState（Redis）；同会话串行、异会话并行
3. `streamEvents()` → **30 种 AgentEventType** 映射为 SSE 业务事件（SSE 业务事件契约：`docs/design/10-sse-contract.md` 待补，M1.6 前置）
4. `REQUIRE_USER_CONFIRM` → 落 `ai_approval` 挂起；`USER_CONFIRM_RESULT` 恢复
5. 结束归档 message/usage；OTel span + capture 模式

### 2.2 审批状态机（DELTA-1/4/5）
```
pending → approved(once) | approved(always→写会话授权+批量放行同规则) | rejected(级联同会话) | expired(超时 fail-closed)
```
- 规则求值：`findLast(wildcard 匹配)` + 默认 `ask`；`deny` 短路；`always` 授权可序列化并在重启后 restore
- **无进展即失败**：恢复流程每步必须推进状态，否则抛不变量错误
- 关闭/崩溃：全部 pending fail-closed；`rawInput` 指纹防调包

### 2.3 工具执行契约（DELTA-3/G5/G16）
- **永不向循环抛异常**：基础设施故障→受控结果（含 duration/错误码）
- 每个工具声明：`risk / timeout / 幂等语义 / 结果上限 / 限流 / 缓存`
- 变更类：**intent → effect → settlement** + 幂等键 + 执行后核验（DLQ 重投/Job 触发）
- Guard：重复调用提醒 + 超时策略（防烧 token 与挂起）

### 2.4 中间件合并语义（DELTA-2）
多中间件命中同一调用：`deny(3) > ask(2) > allow(1)` 取最高档；**理由只取获胜档**；`stop` 首个即 sticky；上下文按顺序累积。

### 2.5 数据流与只读边界
- 只读工具直连 ES/Prom/MySQL(read-only)/Git/LSP/MCP(白名单)
- 变更动作只经 `ApprovalService` 的 settlement 执行；审计只追加

### 2.6 知识入库（双轨）
- 文档轨：`docs/`/旧知识资产（治理后）→ 分块 → Ark embedding → ES 混合检索
- 代码轨：**LSP(jdtls) 精确导航 + JGit blame/历史**（G17）
- 引用校验：回答引用必须能回链（文件/行/方法存在）

### 2.7 观测与合规（G15/G1）
- Capture modes：`metadata` / `sanitized`（默认，先脱敏再截断）/ `full`（需审批）
- failsafe（遥测永不阻塞回合）+ TraceState 上限驱逐；`capture_mode` 落 trace/审计
- 出网审计：内容类别 + 脱敏计数

### 2.8 会话/审计查询（G19）
统一查询服务（精确读/过滤/关系追溯/全文）+ 模型可用查询工具 + 导出；搜索与模型可见历史一致。

### 2.9 反馈回流（G20）
会话备注 + 消息评分（**永不注入模型**）→ 定期沉淀评测集与知识治理。

## 3. 模块与包结构（v0.2）

```
com.myxhs.ai
├── app/            # Application、Web/异常、SSE 编码
├── api/            # chat/session/approval/admin/query 控制器与 DTO
├── agent/          # HarnessAgent 装配、middleware（tracing/audit/permission/cost/guard）
├── policy/         # PolicyEngine（规则/wildcard/优先级）、风险注册表
├── approval/       # ApprovalService 状态机、授权 snapshot/restore、审计
├── tools/          # api(契约) + impl/{es,prom,mysql,rmq,xxljob,lsp,git,mcp,knowledge}
├── query/          # 会话/审计统一查询 + 模型工具 + 导出
├── knowledge/      # 入库管道、hybrid 检索、引用校验、LSP 索引
├── memory/         # DistributedStore 配置、长期记忆治理
├── security/       # 脱敏、capture modes、出网审计
├── infra/          # 各外部系统客户端（含 lsp4j、mcp sdk、rocketmq-tools）
├── store/          # MyBatis-Plus（my_xhs_ai）
├── observability/  # OTel/OTLP、成本与工具指标
├── feedback/       # 会话备注/消息评分
├── guard/          # 重复调用提醒、超时策略
├── eval/           # 评测用例与运行器
└── boot/           # 配置装配、健康检查、优雅停机
```

## 4. 数据模型（增量）

| 表 | 关键字段 | 说明 |
|----|---------|------|
| ai_session | user_id, session_id, title, created_at, updated_at | 会话 |
| ai_message | session_id, role, content, trace_id, tool_calls, tokens_in/out, capture_mode, created_at | 消息归档 |
| ai_approval | session_id, trace_id, tool, kind, raw_input, **raw_input_hash**, patterns, risk, status, requested_at, decided_by, decided_at, decision_reason, result | 审批（状态机） |
| ai_session_grant | user_id, session_id, permission, pattern, created_at, revoked_at | always 授权（可恢复） |
| ai_audit | trace_id, actor, action, target, params(脱敏), content_categories, redaction_count, result, created_at | 审计（只追加） |
| ai_knowledge_doc / chunk | layer, source_path, title, content_hash / doc_id, seq, es_doc_id | 知识（向量在 ES） |
| ai_eval_case / run / result | ... | 评测 |
| ai_feedback | session_id, message_id, actor, kind(session_remark/message_rating), category, note, created_at | 反馈（不注入模型） |

> AgentScope 状态表由框架自管（`agentscope_*` 前缀）。

## 5. 技术决策（ADR v0.2）

| # | 决策 | 理由 | 备选 |
|---|------|------|------|
| 1 | AgentScope 2.0 Java（HarnessAgent） | 内置 Harness/HITL/分布式状态；生态贴合 | Spring AI、LangChain4j、Python |
| 2 | Redis(Sentinel) DistributedStore 管状态+BaseStore | 多副本 CAS；MySQL 仅业务表 | MysqlAgentStateStore |
| 3 | ES dense_vector + BM25 混合检索 | 复用 ES；免新组件 | 独立向量库 |
| 4 | SSE 流式（30 AgentEvent 映射） | 与现有 SSE 体验一致 | WebSocket |
| 5 | HITL：框架 PermissionEngine + **自持久化审批状态机** | 在途由框架、可追溯/多实例恢复由 DB | 纯自研 |
| 6 | v1 不启用沙箱（工具全 HTTP） | 无不可信代码执行 | Docker 沙箱（v2） |
| 7 | 技能仓库 GitSkillRepository(harness-skills) → 平台期 Mysql/Nacos | PR 治理 | MysqlSkillRepository |
| 8 | **模型网关**：provider 抽象 + 超时/重试/熔断/降级/预算/cache 友好 | G3；单一网关耦合风险 | 直连单供应商 |
| 9 | **策略引擎**：有序规则 last-match-wins + 默认 ask + 插件不可改策略 | ADOPT-2/G5 | 硬编码判断 |
| 10 | **工具契约**：never-throw + timeout 声明 + intent/effect/settlement | DELTA-3/G5 | 直接调用 |
| 11 | **代码导航 LSP(jdtls)+JGit 双轨** | G17（精确导航） | 纯 JGit/AST |
| 12 | **MCP 仅桥接 Tools**：白名单 + server-qualified + env 清洗 | G18；复用生态 | 全部自研工具 |
| 13 | **观测捕获模式**默认 sanitized + failsafe + 状态上限 | G15/G1 | 全量外发 |
| 14 | **会话/审计统一查询 + 模型工具 + 导出** | G19 | 仅 DB 查询接口 |
| 15 | **Guard 循环卫生**：重复提醒 + 工具超时策略 | G16 | 无 |
| 16 | **AgentState 持久性**：Redis(Sentinel)+AOF everysec；RPO≤1s / RTO≤30s；写失败 fail-closed | F5/F6：可接受短暂不可用，拒绝状态分叉 | MySQL 权威 + Redis 缓存 |
| 17 | **向量存储分阶段**：v1 ES(knn) ≤2 万 chunk；>5 万或 P95>300ms 迁专用向量库 | D07 量化：ES heap 512MB→1GB，迁移阈值明确 | Milvus/pgvector 起步 |
| 18 | **代码导航分阶段**：v1 tree-sitter/JavaParser+JGit；v1.1 上 jdtls（资源约束） | jdtls 1.2-1.5GB；磁盘/内存紧张时可降级 | 纯 jdtls v1 |
| 19 | **模型网关自研薄层**（Java 内嵌；预算/审计/缓存/降级深度耦合）；上游 OpenAI 兼容，可替换为 LiteLLM/OneAPI | 避免额外组件；边界=OpenAI API | 直接引入开源网关 |
| 20 | **容量/磁盘约束**：AI 组件新增 4-6GB 磁盘，依赖 65G 扩容；不足时降级（限知识/jdtls/自托管 Langfuse 可选） | D07 §2 | 立即全量 AI 组件 |
| 21 | **扩展框架两代**：v1 Java SPI（声明式零信任）+ v2 出进程 Sidecar（借鉴 Reasonix v2 协议）；**禁止 in-process full-trust** | D08 §2 | 直接引第三方代码插件 |
| 22 | **生态吸收治理**：catalog+SHA pin+人工评审+CI 校验+安装≠启用+provenance；吸收优先级 设计>MCP>Skill>Sidecar | D08 §4 | 无治理直接吸收 |
| 23 | **MCP 提升为一等公民（v1.5）**：优先接官方 ES/Prometheus/Grafana MCP；数据库改为**受控只读查询**（AST校验+READ ONLY 事务+只读账号+行数/超时+脱敏+审计），替代"禁止 SQL" | D09 §3（功能对标发现） | 继续全自研工具层 |

## 6. 集成点（v0.2 增量）

| 新增 | 方式 | 权限 |
|------|------|------|
| jdtls（Java LSP） | LSP over stdio（lsp4j） | 只读 |
| MCP servers（白名单） | MCP SDK（本地/远程） | 只读/受策略约束 |
| Langfuse（可自托管） | OTLP | 出网/内网 |
| VictoriaMetrics | PromQL（30d） | 只读 |

## 7. 配置增量

`ai.security.capture-mode=sanitized`、`ai.budget.session-tokens=100000`、`ai.budget.alert-ratio=0.8`、`ai.tools.<name>.timeout/idempotent/max-bytes`、`ai.mcp.servers[].command/allow-tools`、`ai.lsp.jdtls.path`、`ai.model.fallback=deepseek-v4-flash`

## 8. 专项设计进展（2026-09-12 更新）

- ✅ 已产出：`design/01`~`design/09`（模型网关/工具治理审批/观测合规/代码导航/MCP/FMEA/容量成本/扩展框架/生态采纳，见 README 索引）
- ⏳ 待补（M1.6 前置）：`design/10-sse-contract.md`（SSE 业务事件契约）、`design/11-security-authz.md`（鉴权与工具白名单）

## 9. 风险（v0.2）

| # | 事项 | 处理 |
|---|------|------|
| 1 | ES knn 规模/性能 | 小规模先行；`num_candidates` 调优；评测门禁 |
| 2 | jdtls 启动/内存成本 | 常驻单实例 + 按需索引；超时降级 JGit |
| 3 | MCP server 质量参差 | 白名单 + schema 审查 + 契约测试 |
| 4 | 多副本审批恢复 | ai_approval + grants + 无进展不变量；M4 多副本集成测试 |
