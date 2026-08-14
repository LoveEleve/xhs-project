# HANDOFF-AI v2（my-xhs-ai 交接文档）

> 日期：2026-08-14 | 用途：**新会话/新上下文无缝接续**。
> 通读本文件（约 15 分钟）即可接续。所有路径相对 `/data/workspace/my-xhs`。
> 配套：`my-xhs-ai/business-analysis/tech/production-roadmap.md`（M5-M9 规划）、`HANDOFF-AI-v1.md`（早期状态，已被 v2 取代）、`my-xhs-ai/business-analysis/README.md`。

---

## 0. 项目一句话与当前状态

> 给 my-xhs 电商平台建**运营/运维诊断 AI Agent**：查订单/支付/内容/系统指标，多步归因，带证据链、可追溯、不越权、不编造。
> **当前状态：D1-D4 核心 + M5（Durable）+ M6（评测/可观测/门禁）+ M7（安全实证）+ M8-1/2（容器化/故障演练）已完成**（169 个 @Test 方法全绿）。生产路线图 M5-M9 已交付过半。
> **下一步**：M8-3 gateway 接入（外部依赖）、M8-4 UI 薄壳、CI 门禁接入；M9 深化。

---

## 1. 环境与凭据（安全红线）

### 目录
| 路径 | 说明 |
|------|------|
| `/data/workspace/my-xhs` | 唯一 git 仓库（remote: `LoveEleve/xhs-project.git`，分支 main）|
| `my-xhs-ai/` | AI 项目（business-analysis 规划 + docs 学习轨 + tech 详细设计）|
| `my-xhs-ai-app/` `my-xhs-ai-mcp/` `my-xhs-ai-tools/` | 三个代码模块（根 pom 已注册 modules，**已全部提交 git**）|
| `/data/workspace/my-xhs/.env.local` | 凭据唯一来源（gitignored，**已确认 check-ignore OK**）|
| `my-xhs-ai/business-analysis/tech/` | 详细设计：production-roadmap / design-agent-harness / deploy-ai / ci-eval-gate / red-team-report-v1 / security-supply-chain |

### 凭据（`.env.local` 变量，绝不落库/不入 git）
| 变量 | 用途 | 备注 |
|------|------|------|
| `MYXHS_LLM_API_KEY` | **主模型 OpenCode Go**（`opencode.ai/zen/go/v1`，deepseek-v4-flash）——2026-08-14 从 TeamoRouter 切换（按量费改用不起）| **成本红线：仅 deepseek-v4-flash，禁止多模型**（OpenCode Go 订阅 $10/月，$60 月度额度）|
| `TEAMO_API_KEY` | 旧 TeamoRouter key（已不用，.env.local 保留）| |
| `MYXHS_DB_USER/PASSWORD` | 只读账号 `myxhs_ai_ro`（SELECT；已补 my_xhs_cart/my_xhs_product 两库权限）| 真实 MySQL |
| `MYXHS_ROOT_PASSWORD` | 集成测试自愈 seed + DDL 用（root）| 仅测试/建表 |
| `MYXHS_AI_DB_USER/PASSWORD` | **AI 自有库写账号 `myxhs_ai_rw`**（仅 my_xhs_ai 库，M5 Run Store）| 随机密码在 .env.local |
| `MYXHS_ES_PASS` | ES elastic 认证 | |
| `ARK_PLAN_API_KEY/BASE_URL/EMBEDDING_MODEL` | RAG embedding：火山 Agent Plan `/api/plan/v3` + `doubao-embedding-vision-large`（2048 维）| |

### 基础设施（真实，远端）
| 服务 | 地址 | 备注 |
|------|------|------|
| MySQL | `21.130.247.89:3306`（orders 分片 ds0..3×t_order_0..3=16 节点；事件表 t_cart_event/t_product_behavior/t_payment_event/t_note_event 已建）| 只读 myxhs_ai_ro / 写 myxhs_ai_rw（my_xhs_ai 库）|
| ES | `21.130.247.89:19200`（8.19.19）| RAG |
| Prometheus | `21.130.247.89:19090`（观测工具数据源，无认证但 iptables 白名单）| 含 textfile 管道（MQ 指标）+ mysqld-exporter（主 9104/从 9105）|
| SkyWalking | `21.130.247.89:12800` | 跨服务链路（B3 面备用）|
| 模型 | OpenCode Go `opencode.ai/zen/go/v1`（deepseek-v4-flash）| Bearer MYXHS_LLM_API_KEY |
| Embedding | 火山 `ark.cn-beijing.volces.com/api/plan/v3` | Bearer ARK_PLAN_API_KEY |

> ⚠️ 本地无 docker/MySQL（构建/DB 验证留部署环境/远端）。**git 提交前必查 `.env.local` 未被跟踪**（已确认）。

---

## 2. 代码结构与端口

### 模块
| 模块 | 端口 | 职责 |
|------|:--:|------|
| `my-xhs-ai-tools` | — | 共享真实工具纯类：OrderMetricsTool/PaymentMetricsTool/ContentInteractionTool/BaselineWindowTool/EventAnalyticsTool(A1-A3)/PrometheusQueryTool(B2-B4)/MetricTimeWindow/MetricWindow/ObsToolAccess/MetricToolAccess 接口 |
| `my-xhs-ai-app` | 19020 | AI 核心：模型/路由/Agent Harness/Run Store/RunManager/评测/指标/SSE |
| `my-xhs-ai-mcp` | 19021 | MCP 工具服务（Streamable HTTP `/mcp`，认证+审计；**13 个工具**）|

### my-xhs-ai-app 关键类
| 类 | 职责 |
|----|------|
| `config/LlmGatewayConfig` | ChatModel/StreamingChatModel bean（OpenCode Go，超时可配 `myxhs.ai.llm.timeout-seconds`）——原 TeamoRouterModelConfig 已改名 |
| `config/RouterConfig` | IntentRouter bean + LLM 兜底（默认关）|
| `config/MetricToolAccessConfig`/`ObsToolAccessConfig` | 工具访问开关 mode=mcp(默认)/direct |
| `config/HarnessConfig` | AgentHarness 装配（预算/单价/容忍次数/RunStore/modelName）|
| `config/RunStoreConfig` | **第二数据源**（my_xhs_ai 库，写账号，密码启动校验）|
| `service/agent/harness/*` | **D4 核心**：AgentHarness（状态机/executeLoop/resume/取消 token/截断）/LoopCtrl/LoopDetector/PolicyGuard/ToolResultRegistry/EvidenceChain/AgentRun/AgentStep/AgentDecision/AgentDecisionCodec/HarnessEvent/TerminationReason/RunStatus |
| `service/run/RunManager` | **M5 异步化**：提交即返回 runId、事件缓冲、单消费者订阅、TTL 清理、cancel、启动崩溃恢复（claimRunning 原子认领）|
| `service/run/RunMetrics` | **M6-3 指标**：runs_total{status}/runs_running/tokens_total/cost_total/run_duration |
| `service/store/RunStore`/`JdbcRunStore` | **M5 Run Store**：ai_run/ai_step（messages 快照 checkpoint、tokens_used、last_activity_at 心跳、findRunningStale、claimRunning）|
| `service/mcp/McpClient`/`McpToolBridge`/`McpObsBridge` | MCP 薄协议客户端（会话自愈）+ 业务/观测桥接 |
| `service/router/IntentRouter` | 规则路由：固定→确定性工具；归因→AGENT |
| `service/rag/*` | RAG：检索/入库/回答闭环（口径问答带引用）|
| `service/QueryWindowExtractor` | 确定性当前窗口（单一事实源）|
| `eval/*` | **M6 评测**：EvalCase/EvalCaseLoader/EvalAsserter（硬断言+数字一致性）/EvalRunner（报告+指标）/ToolUsageAnalyzer（发散检测）/EvalGate（PR 门禁阈值）|
| `controller/*` | 见端点表 |

### 端点（app:19020）
| 端点 | 说明 |
|------|------|
| `GET /api/ai/health` | 健康 |
| `POST /api/ai/chat` `/chat/stream` | 对话/SSE（D1 保留）|
| `POST /api/ai/agent` | AiServices 旧 agent 路径（D1 保留）|
| `POST /api/ai/mcp/check` | MCP 全链路验证（app→桥→mcp→真库）|
| `POST /api/ai/query` | 主路由（metric→确定性 / agent→调查）|
| `POST /api/ai/agent/run` | **同步 Harness 端点**（D4）|
| `POST /api/ai/agent/run/stream` | **同步 SSE**（D4，fixed 20 并发）|
| `POST /api/runs` | **M5-2 异步提交**：立即返回 runId |
| `GET /api/runs/{id}` | run 状态/步骤/答案（RUNNING 或完整视图）|
| `DELETE /api/runs/{id}` | **M5-3 协作式取消**（CANCELLING→CANCELLED）|
| `GET /api/runs/{id}/stream` | SSE 订阅（缓冲补发+实时；单消费者 409；30min 超时）|
| `POST /api/ai/rag/ingest/search/search-dense/search-hybrid/answer` | RAG 系列 |
| `GET /actuator/prometheus` `/actuator/health` | **M6-3 指标**/健康 |

### MCP（mcp:19021，13 工具）
- 认证：`MCP_API_KEY`（未设 dev 放行 WARN；**生产必设**）；审计：`[mcp-audit] tool/window/costMs`
- 工具：`order.query_volume` / `payment.success_rate` / `content.interaction` / `baseline.window` / `funnel.conversion` / `payment.failures` / `content.publish_events` / `service.http_errors` / `service.http_latency` / `mq.consumer_lag` / `mq.dlq_backlog` / `mysql.replication_lag` / `mysql.deadlocks`
- 协议要点：`Accept: application/json, text/event-stream` + `Mcp-Session-Id`（initialize 响应头）；异步 202+SSE

---

## 3. 架构摘要

```
用户 → /api/runs（异步）→ RunManager（事件缓冲）→ AgentHarness（状态机）
  ├─ THINK(LLM JSON 决策, OpenCode Go deepseek-v4-flash)
  ├─ VALIDATE(PolicyGuard deny-by-default allowlist + 参数白名单)
  ├─ TOOL(McpToolBridge/McpObsBridge → my-xhs-ai-mcp 13 工具 → 真实 MySQL/Prometheus)
  ├─ 存在性校验(答案 evidenceRefs 必须命中 ToolResultRegistry)
  ├─ LOOPCHECK(预算三重封顶 + 循环检测)
  └─ SSE 推送(HarnessEvent: RUN_STARTED→THINK/TOOL/ANSWER→COMPLETED/PARTIAL/FAILED/CANCELLED)
横切：Run Store（ai_run/ai_step checkpoint+心跳，崩溃自动恢复）、RunMetrics（Prometheus）、
     每请求 traceId、只读账号、MCP 认证+审计、版本追溯（model/prompt/tools）
评测（M6）：eval/cases.yaml（18 条 smoke+3 security）→ EvalRunner → EvalGate 阈值 → CI(-Peval-gate)
```

**核心原则**（写码必守）：
1. 模型只能调固定工具（allowlist），永不执行任意代码/SQL/PromQL（CodeAct 红线）
2. 数字必须来自工具结果（存在性校验 + 幻觉检测），模型编造是红线
3. **成本红线：LLM 仅 deepseek-v4-flash**（OpenCode Go），禁止多模型
4. 每步改动必须有评测安全网（M6 门禁）

---

## 4. 已交付里程碑

### D1-D4（核心引擎，2026-08-13 完成）
- **D1**：LangChain4j 1.0.0 × Boot 3.2.5 × JDK17（手工集成）；三真实指标工具（order 61→46 数据漂移已更新基线/payment/content）；IntentRouter 混合路由
- **D2**：my-xhs-ai-mcp（MCP SDK 0.18.3，认证+审计+契约测试）；自研薄协议客户端（SDK fat jar 冲突）
- **D3**：RAG（ES BM25/dense/hybrid RRF；指标字典 19 条；回答带引用+拒答）
- **D4**：**AgentHarness 全状态机**（预算三重封顶/循环检测两模式/存在性校验/证据链+反证+不确定性/HITL 门/PolicyGuard）；确定性窗口注入（QueryWindowExtractor + baseline.window 工具）；SSE 端点（并发受限）；**A 面事件流水工具**（A1 漏斗/A2 支付失败/A3 发布，对方 2026-08-14 补齐 4 张事件表）

### M5 Durable（2026-08-14）
- **M5-1 Run Store**：my_xhs_ai 库 + ai_run/ai_step（messages 快照 checkpoint、tokens_used、last_activity_at 心跳）+ 版本追溯（versions_json）
- **M5-2 异步化**：POST /api/runs 立即返回 + GET 状态 + SSE 订阅（单消费者 409/TTL 清理/异常终态补发）
- **M5-3 取消**：DELETE（协作式：当前步完成后生效，证据摘要保留）
- **M5-4 崩溃恢复**：findRunningStale + claimRunning 原子认领 + checkpoint 重放（kill -9 实测：10 步后恢复续跑，预算不重置）

### M6 评测与可观测（生产级第一道门禁）
- **M6-1 评测框架**：YAML 评测集（18 条）+ 分层断言（硬：状态/证据/关键词；软：答案数字 vs 工具证据=幻觉检测，剥 [ev_xxx]/跳过百分比）
- **M6-2 工具误用分析**：ToolUsageAnalyzer（发散检测：同工具不同窗口>4）+ 质量指标（avgTokens/avgDuration/发散率）
- **M6-3 可观测**：/actuator/prometheus（runs_total/tokens/cost/run_duration P95）
- **M6-4 PR 门禁**：EvalGate（阈值 幻觉≤10%/通过≥60%/完成≥40%，百分比语义）+ `-Peval-gate` profile（默认 mvn test 排除）+ CI 模板（ci-eval-gate.md）

### M7 安全实证
- **M7-1 红队用例进评测集**：sec_prompt_leakage/sec_unauthorized_tool/sec_pii_probe（notRegex 断言扩展）
- **M7-2 真库实证**：3/3 拦截（注入拒绝+零泄漏、越权未执行、PII 零泄漏）——报告 red-team-report-v1.md
- **M7-3 供应链/密钥**：cyclonedx SBOM（target/bom.json 63 组件）、密钥零泄漏扫描（历史+工作区）、观测认证建议（iptables 持久化+安全组，运维执行）

### M8 部署（进行中）
- **M8-1 容器化**：AI 模块独立 Dockerfile（分阶段，tools 依赖链）+ compose 片段（config/docker-compose.ai.yml，host 网络+健康检查）+ deploy-ai.md
- **M8-2 故障演练**：MCP 不可用（ERROR 回填+零编造+不确定性）、模型不可用（FAILED 明确降级）实测通过

### 测试
**169 个 @Test 方法全绿**（tools 39 + app 118 + mcp 12）。surefire 运行数略少（评测测试 @Tag(eval-gate) 默认排除、真库集成测试无凭据自动跳过）——**测试数随演进变化，以最新 `mvn test` 为准，不写死**。真库集成测试需 `.env.local` 注入（CI 无凭据自动跳过/排除）。**评测测试（EvalSmokeRunTest/EvalGateRunTest）@Tag(eval-gate) 默认排除，-Peval-gate 才跑**。

---

## 5. 关键决策与红线（ADR 与记录）

| 决策 | 内容 |
|------|------|
| 成本红线（2026-08-14） | **LLM 仅 deepseek-v4-flash（OpenCode Go）**，禁止多模型；模型分层（M9 D4 决策）冻结 |
| Provider | TeamoRouter（按量费）→ OpenCode Go（$10/月订阅，$60 月度额度）——零代码改动（OpenAI 兼容）|
| ADR-001..006 | 模型 Provider / LangChain4j 主 / MCP SDK jackson2 / Durable=V1 本地状态机 / 自研平台（非 opencode/pi 底座）/ 单组织 |
| MCP 版本 | 0.18.3（Spring 集成模块未达 2.0 GA；2.0 后升级）|
| 工具访问 | `myxhs.ai.tools.mode=mcp`(默认)/direct |
| 路由 | 归因优先 AGENT；模糊→LLM 兜底（默认关）|
| 评测阈值 | 幻觉≤10%/通过≥60%/完成≥40%（保守，校准后收紧）|
| 超时 | LLM 60s（可配，重试一次最坏单步 120s）；SSE 30min（覆盖最坏 run）|
| 工具结果截断 | 模型可见 400 字符字段边界截断（registry 完整保留；token 成本收益）|
| 崩溃恢复 | 心跳 10min 超时判定 + claimRunning 原子认领（双实例防双份）|

---

## 6. 深度 review 教训（防重踩）

1. **免费模型工具调用不可靠会虚构** → 付费 flash + **存在性校验**（确定性兜底，不靠模型自觉）
2. **模型发散是常态**（15 步预算跑满 BUDGET_STEPS 反复出现）→ prompt 收敛压力 + 评测发散率监控（阈值校准中）；**慢的根源是发散不是上下文体积**（截断优化实测延迟无改善，token 成本有收益）
3. **报告要用实测验证**（DLQ 指标、mysql-exporter、B2 就绪度——D0 文档多次乐观/过时）
4. **拒绝性回答会提及工具名**（红队断言查"成功语义"而非"不含工具名"）
5. **Map.of 禁止 null 值**（view 序列化 NPE——已改 HashMap）
6. **Jackson 会把 isXxx() 序列化成属性**（AgentDecision answer/toolCall 破坏快照恢复——@JsonIgnore + ignoreUnknown 兼容旧数据）
7. **重放计步语义**（崩溃恢复只 THINK 计步，TOOL/ANSWER 不计）
8. **评测测试的 api-key 用占位符** `${MYXHS_LLM_API_KEY:test-key}`（TestPropertySource 优先级>env，直接 test-key 覆盖真 key→401）；真库评测 @Tag 默认排除
9. **部署 compose healthcheck 依赖 actuator**（mcp 需加 actuator）；**后端 Dockerfile 模板缺 tools 依赖**（AI 模块独立 Dockerfile）
10. **YAML 双引号正则 \d 非法转义**（单引号）
11. **surefire 下 actuator prometheus HTTP 端点 404 为环境差异**（本地 jar 正常，测试用 MeterRegistry 断言）
12. **eval-gate 门禁单位语义**（指标是百分比，阈值也按百分比）

---

## 7. 已知问题/边界（如实）

| 项 | 说明 |
|----|------|
| 模型发散 | 15 步发散常见（BUDGET_STEPS 兜底）——收敛压力 prompt + 发散率评测监控；收敛后速度恢复（单步约 8s）|
| A1/A2 真数据 | 依赖业务流量（事件表当前 0 行；工具链路/口径已闭环，验收语义达标）|
| B1 慢查询 | 缺摄入管道（slow_query_log 已开无采集；需求单已发，运维建）|
| MCP_API_KEY 生产必设 | dev 未设放行（WARN）|
| 只读账号白名单/轮换 | 未做（建议列入排期）|
| 观测端点认证 | iptables 未持久化（建议 iptables-persistent+安全组）|
| traceId 单服务 | OTel/traceparent 跨服务 = D6 |
| 审计是应用日志 | 独立不可变审计流 = D6 |
| UI | 未开始（M8-4）|
| gateway /api/ai/** | 未配（外部依赖，M8-3）|
| Langfuse/Temporal | PoC 未做（决策点 D1/D2）|
| 工具结果截断 | 400 硬编码（可配化待办）；SSE TOOL 事件全量推送（UI 层截断）|
| 内存态 | RunEntry TTL 1h 清理（store 保留）|
| E2E 评测时长 | 7 条锚点集 20-40 分钟（CI 需 1h timeout）|

---

## 8. 剩余工作

### M8 剩余
- **M8-3 gateway 接入**（外部：/api/ai/** 路由 + 角色 L1/L2）
- **M8-4 UI 薄壳**（消费 SSE，证据链可点 + HITL 审批按钮；前端项目 frontend/）
- **CI 门禁接入**（模板 ci-eval-gate.md 就绪，gitlab-ci 加 job）
- 故障演练补项：模型限流（真实超限窗口）、MySQL 宕（机制同 MCP 演练）
- Dockerfile 镜像构建验证（部署环境）

### M9 深化
- 受控进程工具（插件化安全落地：ProcessBuilder + 参数白名单 + L3 HITL 门）
- 记忆（长期上下文）
- B1 慢查询完整闭环（等管道）
- A1/A2 真数据验收（等流量）

### 跨里程碑待办
- 阈值校准（真库评测跑几轮后收紧）
- 工具结果截断长度配置化
- Langfuse PoC（D1）/ Temporal PoC（D2）
- 密钥轮换策略（对方）
- SBOM 漏洞扫描 CI（schedules 模板就绪）

---

## 9. 外部依赖（对方配合项）

| 项 | 归属 | 状态 |
|----|------|------|
| 慢查询摄入管道（B1） | 运维 | 需求单已发 |
| gateway /api/ai/** 路由+角色 | gateway 团队 | 未定 |
| MCP_API_KEY 生产设置 | 部署 | 未定（生产必设）|
| 只读账号白名单/轮换 | DBA/运维 | 建议已提 |
| 观测端点认证（iptables 持久化+安全组） | 运维 | 建议已提 |
| 部署环境（docker/云主机） | 运维 | 未定（本地无 docker）|
| DLQ -1 哨兵值（应用侧改代码） | 微服务侧 | 已反馈（-1→0 或不输出）|
| A 面事件表（t_*_event 4 张）| 微服务侧 | ✅ 已建（2026-08-14）|
| T-058/T-059 修复 | 微服务侧 | ✅ 已部署 |

---

## 10. 如何运行/验证（新会话速查）

```bash
cd /data/workspace/my-xhs
set -a; source .env.local; set +a    # 注入全部凭据（gitignored）

# 全量测试（不含真库评测；数字以输出为准）
mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp

# 真库评测门禁（需真 key，20-40 分钟；EvalSmokeRunTest+EvalGateRunTest）
mvn test -pl my-xhs-ai-app -Peval-gate

# 打包+启动（改动 tools 先 install）
mvn -pl my-xhs-ai-tools install -DskipTests
mvn -pl my-xhs-ai-app,my-xhs-ai-mcp package -DskipTests
java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &   # 19021
java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar &   # 19020

# 真实 E2E
curl -s http://127.0.0.1:19020/api/ai/health
RID=$(curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d '{"message":"为什么订单量下降了？"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['runId'])")
curl -s http://127.0.0.1:19020/api/runs/$RID          # 轮询至终态
curl -sN http://127.0.0.1:19020/api/runs/$RID/stream  # SSE 订阅
curl -s http://127.0.0.1:19020/actuator/prometheus | grep myxhs_ai_   # 指标
curl -s -X DELETE http://127.0.0.1:19020/api/runs/$RID # 取消

# 清理残留进程
fuser -k 19020/tcp 19021/tcp
```

> ⚠️ 打包前若改了 tools 模块：`mvn -pl my-xhs-ai-tools install` 再打 app/mcp。
> ⚠️ 删真库记录（E2E 后清理）：`mysql -u myxhs_ai_rw -p"$MYXHS_AI_DB_PASSWORD" -e "DELETE FROM my_xhs_ai.ai_step; DELETE FROM my_xhs_ai.ai_run;"`

---

## 11. 学习轨与参考

- `my-xhs-ai/business-analysis/tech/production-roadmap.md`：**M5-M9 详细规划**（目标/技术栈/难点/方案/验收/决策点 D1-D6/参考调研对照）
- `my-xhs-ai/business-analysis/tech/design-agent-harness.md`：Harness 详细设计
- `my-xhs-ai/business-analysis/tech/deploy-ai.md`：部署文档（构建/网络安全/演练）
- `my-xhs-ai/business-analysis/tech/ci-eval-gate.md`：评测门禁 CI 接入模板
- `my-xhs-ai/business-analysis/tech/red-team-report-v1.md`：红队实证报告
- `my-xhs-ai/business-analysis/tech/security-supply-chain.md`：SBOM/密钥/观测认证
- `my-xhs-ai/docs/INDEX.md`：17 phase 学习轨；`PLAN.md`：v5 总规划（9 面试题）
- 参考调研（不照搬）：Anthropic《Building Effective Agents》、OWASP LLM Top10 2025、Langfuse、Temporal、Promptfoo——对照结论在 production-roadmap §10

---

## 12. 面试/作品集资产

- **难点/亮点/面试点证据化**：production-roadmap §14（九问 → 代码/测试/E2E 证据映射，可当场复现）
- **亮点**：不编造=架构保证（存在性校验）、证据链+反证+不确定性、确定性窗口注入（同题不同窗口结论相反 41.9% vs 63.9% 实证）、全真实数据闭环、Agent 反向发现 DLQ -283 哨兵值 bug、促成 4 张事件表+T-058/T-059 修复
- **作品集缺口**（全部是 M8/M9 交付物）：C4 图、AgentScope 对照、Temporal kill 实验、300+ 评测集、Langfuse trace、成本/容量报告、演示视频

---

## 13. 交接检查单（新会话开始前）

- [ ] 通读本文件（v2）
- [ ] 确认代码可用：`ls my-xhs-ai-app/src my-xhs-ai-mcp/src my-xhs-ai-tools/src`（已全部提交 git）
- [ ] `source .env.local`（凭据就绪；MYXHS_LLM_API_KEY=OpenCode Go，成本红线仅 flash）
- [ ] 读 `production-roadmap.md` 定位当前里程碑（M8-3 起）
- [ ] 遵守操作纪律：每单元小步 + 深度 review + 评测安全网
- [ ] 改动 tools 模块记得 install
- [ ] 提交 git 前确认 `.env.local` 未跟踪（`git check-ignore .env.local`）
- [ ] 密钥/凭据绝不落库、不入 git、不进文档
