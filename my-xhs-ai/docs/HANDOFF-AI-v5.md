# HANDOFF-AI v5（my-xhs-ai 交接文档·详细版）

> 日期：2026-08-16 | 用途：**新会话/新上下文无缝接续**。本文件是当前版本（v4 之后：M10-M14 全部完成 + 多轮深度 review 闭环）。
> 所有路径相对 `/data/workspace/my-xhs`。取代 v4（v4/v3/v2 保留仅作历史）。
> 通读约 25 分钟。配套文档索引见 §11。

---

## 0. 项目一句话与当前状态

> 给 my-xhs 电商平台建**运营/运维诊断 AI Agent**：查订单/支付/内容/系统指标，多步归因，带证据链、可追溯、不越权、不编造。
> **M10-M14 规划全部完成**（会话记忆 → 工具注册表 → HITL 审批 → 多智能体 → 评测闭环），每个里程碑经写前设计/写前 review/写后深度 review/二轮 review。
> **226 个 @Test 全绿**（tools 50 + app 165 + mcp 11；口径=非 eval-gate 套件，其中 6 个集成测试无凭据自动跳过；eval-gate tag 的 4 个真库测试类另计）。
> **下一步（顺序已定）**：① judge Spring 装配 + badcase 路径配置化（P2 收尾）→ ② nightly 全量评测（100 条）首跑 → ③ 演示视频/作品集资产。

---

## 1. 环境与凭据（安全红线）

### 目录
| 路径 | 说明 |
|------|------|
| `/data/workspace/my-xhs` | 唯一 git 仓库（remote: `LoveEleve/xhs-project.git`，分支 main）|
| `my-xhs-ai/` | 规划（business-analysis）+ 学习轨（docs）+ 详细设计（tech）|
| `my-xhs-ai-app/` `my-xhs-ai-mcp/` `my-xhs-ai-tools/` | 三个代码模块（已全部提交 git）|
| `frontend/` | React19+antd6（AI 诊断台薄壳在 `frontend/src/pages/ai/`）|
| `/data/workspace/my-xhs/.env.local` | 凭据唯一来源（gitignored，已确认 check-ignore OK）|
| `my-xhs-ai/business-analysis/tech/` | **规划/设计/探索文档全集**（见 §11 索引）|

### 凭据（`.env.local` 变量，绝不落库/不入 git）
| 变量 | 用途 | 备注 |
|------|------|------|
| `MYXHS_LLM_API_KEY` | **主模型 OpenCode Go**（`opencode.ai/zen/go/v1`，deepseek-v4-flash）| **成本红线：仅 flash，禁止多模型** |
| `TEAMO_API_KEY` | 旧 TeamoRouter key（已不用，保留）| |
| `MYXHS_DB_USER/PASSWORD` | 只读账号 `myxhs_ai_ro`（SELECT）| 真实 MySQL |
| `MYXHS_ROOT_PASSWORD` | 集成测试自愈 seed + DDL（root）| 仅测试/建表 |
| `MYXHS_AI_DB_PASSWORD` | AI 自有库写账号密码（用户 `myxhs_ai_rw` 由 yml 默认兜底 `${MYXHS_AI_DB_USER:myxhs_ai_rw}`）| my_xhs_ai 库 |
| `MYXHS_ES_PASS` | ES elastic 认证 | |
| `ARK_API_KEY` | **火山模型 API key（.env.local 实际存在，勿漏）** | 备用/未用主路径 |
| `ARK_PLAN_API_KEY/BASE_URL/EMBEDDING_MODEL` | RAG embedding + 语义路由降级 embedding（doubao-embedding-vision-large 2048 维，**单批 ≤10 条**）| |
| `MCP_API_KEY` | **非 .env.local 变量**——部署环境设置（dev 未设放行 WARN，生产必设）| |
| `MYXHS_LOG_SEARCH_FILES` | **非 .env.local 变量**——启动时手动 export（service=日志文件绝对路径 CSV；不配则 log.search 全拒）| |

### 基础设施（真实，远端）
| 服务 | 地址 | 备注 |
|------|------|------|
| MySQL | `21.130.247.89:3306`（orders 分片 ds0..3×t_order_0..3；事件表 ×4 已建）| 只读 myxhs_ai_ro / 写 myxhs_ai_rw |
| ES | `21.130.247.89:19200`（8.19.19）| RAG |
| Prometheus | `21.130.247.89:19090` | 观测数据源 |
| SkyWalking | `21.130.247.89:12800` | 跨服务链路（备用）|
| 模型 | OpenCode Go `opencode.ai/zen/go/v1` | Bearer MYXHS_LLM_API_KEY |
| Embedding | 火山 `ark.cn-beijing.volces.com/api/plan/v3` | Bearer ARK_PLAN_API_KEY |

> ⚠️ 本地无 docker/MySQL（构建/DB 验证留部署环境/远端）。**git 提交前必查 `.env.local` 未被跟踪**。
> ⚠️ 评测/集成测试依赖**本地 MCP 服务（19021）**——跑 eval-gate/对比评测前先启动（§10）。

---

## 2. 代码结构与端口

### 模块
| 模块 | 端口 | 规模(main/test, 2026-08-16 实测) | 职责 |
|------|:--:|:--:|------|
| `my-xhs-ai-tools` | — | 1818/897 | 共享工具纯类：业务×3、事件流水×3、baseline、观测×6、log.search、**dlq.redeliver（M11）**、**ToolRegistry/AgentToolCatalog/AgentToolBinder（M12）**、ToolJson/窗口/白名单 |
| `my-xhs-ai-app` | 19020 | 6444/4391 | AI 核心：路由/**AgentProfiles+AgentDispatcher（M13）**/Harness/**HITL 审批（M11）**/会话（M10）/RunManager/RunStore/评测/**EvalJudge+BadCaseCollector（M14）**/SSE |
| `my-xhs-ai-mcp` | 19021 | 305/306 | MCP 服务：14 工具（**从注册表导出，M12**，Streamable HTTP，认证+审计）|
| `frontend/` | 5173(dev) | pages/ai 447 | AI 诊断台薄壳（SSE/证据链/**会话标识（M10）**/**审批卡片（M11）**/?run=/?conv=）|

### 关键类（app 模块）
| 类 | 职责 |
|----|------|
| `config/RouterConfig` | 三阶路由装配：L0 规则 → L1 **LLM 分类（默认开）** → L2 语义降级 → L3 默认引导 |
| `config/AgentToolRegistryConfig` | **M12 工具注册表装配**（catalog + 三桥执行器，AgentToolBinder 同源）|
| `config/DlqRedeliverConfig` | **M11 dlq.redeliver 受控执行装配**（管理通道 URL 配置化，未配置 ERROR）|
| `service/router/IntentRouter` | 三阶路由（L0 指标/归因/ID 强信号 → L1 LLM 分类主路径 → L2 语义 → L3 引导）|
| `service/agent/profile/AgentProfiles` | **M13 双 Agent 画像**（BUSINESS/OPS/FULL；prompt 变体=通用规则段+领域工具列表段）|
| `service/agent/profile/AgentDispatcher` | **M13 领域分派**（业务/排障信号矩阵；traceId 32hex → OPS）|
| `service/agent/harness/AgentHarness` | 状态机：executeLoop 调度 + handler 拆分；**L3 挂起（M11）**；**profile 参数（M13）**；resume 恢复画像 |
| `service/run/RunManager` | 事件缓冲/单消费者/cancelStream/崩溃恢复/**会话锁+消息落库（M10）**/**approve（M11）**/**领域分派（M13）** |
| `service/conversation/ConversationService` | **M10 多轮上下文（摘要+历史，无工具原文）+ 规则摘要 + 首问保留** |
| `service/store/JdbcRunStore` | Run Store（checkpoint/心跳/claimRunning/finalAnswer/**approval_json（M11）**）|
| `eval/EvalAsserter` | 数字一致性（数值语义/日期时间/traceId 剥离/量级窗口）——多轮 review 修复 |
| `eval/EvalJudge` | **M14 LLM-as-judge（0-5 主观质量分，正则解析）** |
| `eval/BadCaseCollector` | **M14 bad case 回流（质量失败→YAML 追加，id 自动递增）** |
| `eval/EvalGate` | 门禁阈值（10/60/40，实测校准文档化）|

### 端点（app:19020，全量核对）
| 端点 | 说明 |
|------|------|
| `GET /api/ai/health` | 健康 |
| `POST /api/runs` | 提交诊断（返回 runId+**conversationId**；问候/超范围直答也落库）|
| `GET /api/runs/{id}` | 状态/步骤/答案（**WAITING_APPROVAL + pendingTool/args（M11）**；内存 miss 回退 Run Store）|
| `POST /api/runs/{id}/approve` | **M11 HITL 审批（approve→resume 执行/reject→CANCELLED+审计；非挂起 409）** |
| `DELETE /api/runs/{id}` | 协作式取消 |
| `GET /api/runs/{id}/stream` | SSE 订阅（单消费者 409；断开立即释放；**WAITING_APPROVAL 事件（M11）**）|
| `GET /api/conversations/{convId}` | **M10 会话详情（消息+summary+runId 关联）** |
| `GET /api/conversations?userId=` | **M10 会话列表** |
| `POST /api/ai/query` | 路由直答（指标确定性/问候/超范围直答带 runId/AGENT 模型调用——**同走 RunManager 分派，无两入口分裂**）|
| `POST /api/ai/chat` / `POST /api/ai/chat/stream` | D1 对话/SSE（保留）|
| `POST /api/ai/agent` / `POST /api/ai/agent/run` / `POST /api/ai/agent/run/stream` | D4 同步 Agent 端点（保留）|
| `POST /api/ai/mcp/check` | MCP 全链路验证（app→桥→mcp→真库）|
| `POST /api/ai/rag/ingest` `/search` `/search-dense` `/search-hybrid` `/answer` | RAG 系列 |
| `GET /actuator/prometheus` `/actuator/health` | 指标/健康 |

### MCP 工具（mcp:19021，14 个，注册表导出）
`order.query_volume` `payment.success_rate` `content.interaction` `baseline.window` `funnel.conversion` `payment.failures` `content.publish_events` **`log.search`** `service.http_errors` `service.http_latency` `mq.consumer_lag` `mq.dlq_backlog` `mysql.replication_lag` `mysql.deadlocks`
> L3（dlq.redeliver 等）不上 MCP（仅 app 侧 Harness 经审批调用）。

### 配置项（application.yml，myxhs.ai.*）
`llm.timeout-seconds`(60) / `router.llm-fallback.enabled`(true) / `router.semantic-threshold`(0.42) / `agent.max-steps|max-tokens|max-cost|price-per-1k-tokens|max-invalid-answers|tool-result-max-len` / `tools.mode`(mcp/direct) / `rag.*` / **`log-search.files`** / **`hitl.dlq-redeliver.url`（M11）** / `eval.gate.*`(10/60/40) / **`eval.judge.enabled`（M14，默认 false，尚无装配载体——P2）**

---

## 3. 架构（重构后 + M10-M14）

```
前端薄壳 → POST /api/runs（convId 多轮）
  → 三阶意图路由（L0 规则/L1 LLM 分类主路径/L2 语义降级/L3 默认引导）
  → AgentDispatcher（M13 领域分派：业务归因/技术排障）
  → RunManager（事件缓冲/单消费者/cancelStream/崩溃恢复/会话锁）
  → AgentHarness（executeLoop + handler 拆分 + profile 参数）
      → PolicyGuard（deny-by-default + 参数白名单 + L3 审批挂起[M11] + 画像子集过滤[M13]）
      → ToolRegistry（M12 单一事实源：catalog 元数据 + 执行器绑定）
      → MCP 14 工具（真实数据源）
      → 存在性校验（registry）+ DECLINE 零证据豁免
  → SSE 事件流（含 WAITING_APPROVAL/APPROVAL_RESULT）
横切：Run Store（checkpoint/心跳/finalAnswer/approval_json）、Conversation Store（M10）、
      RunMetrics、traceId（仅同步线程）、MCP 认证+审计
评测：EvalRunner（100 条分层集 + LLM-judge[M14] + badcase 回流[M14]）+ eval-gate 门禁
```

### 核心机制（面试/写作必讲）
1. **三阶意图路由**：LLM 负责理解，规则只保确定性
2. **存在性校验**：ANSWER 的 evidenceRefs 必须命中 ToolResultRegistry（本轮 run 级）
3. **DECLINE 零证据豁免**：拒答/超范围明确说明即可，不被校验逼着"凑证据"
4. **受控工具**：log.search 白名单/无 shell；**dlq.redeliver 命令模板写死+参数白名单+审批门（M11）**
5. **历史追溯**：finalAnswer 落库/内存 miss 回退/全量落库
6. **崩溃恢复**：checkpoint + 心跳 + 原子认领 + 重放
7. **M10 多轮**：历史注入=结论无工具原文（跨轮数据必须重新查）；会话锁串行 409
8. **M12 注册表**：工具元数据/校验/执行器单一事实源，MCP 列表导出，新增工具零改 Harness
9. **M11 HITL**：挂起=终止+状态标记（线程释放），审批=resume 注入执行（复用 checkpoint，EXECUTED 防重）
10. **M13 多 Agent**：共享引擎+参数化 profile（prompt 变体+工具子集），规则分派零成本，越权 deny 兜底
11. **M14 评测闭环**：100 条分层集/LLM-judge（主观维度）/bad case 回流/阈值实测校准

---

## 4. 已交付里程碑（含重构 + M10-M14）

### D1-D4 / M5-M9
D1 三真实指标工具 + 路由；D2 MCP + 薄协议客户端；D3 RAG；D4 AgentHarness 状态机；
M5 Durable；M6 评测门禁；M7 安全；M8 容器化/UI 薄壳；M9-1 受控日志检索。
（细节见 v4 对应章节）

### M10-M14（2026-08-15~16 会话，全部完成）
| commit | 里程碑 | 内容 |
|--------|--------|------|
| `24b7c1a` | 前置债 | **eval-gate 跑绿**：profile 修复（空 excludedGroups 覆盖）+ 数字一致性误报修复（8 种形态）+ 限流重试 |
| `90eadc3` | M10 | 会话与记忆：ai_conversation/ai_message、多轮注入（无工具原文）、规则摘要、同会话 409、会话端点 |
| `92a099f`+`a185392` | M10 review | **2 P0 + 1 P1**：历史注入方向（取最早 N 条）、当前问题重复注入、session_id 时序竞态 |
| `130d694` | M12 | 工具注册表：ToolSpec/ToolRegistry/AgentToolCatalog（17 条）/AgentToolBinder、PolicyGuard 注册表驱动、MCP 列表导出（契约零变化）|
| `b640071` | M12 review | **1 P0**：校验规则漂移（keyword 白名单复制而非委托）→ 委托 DirectLogSearchAccess |
| `6599210` | M11 | HITL 审批闭环：WAITING_APPROVAL/resume 注入执行/approve 端点/审计（approval_json）/dlq.redeliver 受控执行/前端审批卡片 |
| `fd2760f` | M11 review | **2 P1**：挂起态会话锁提前释放 + 前端流悬挂（状态枚举变化须审计所有"终态判断点"）|
| `a51acad` | M13 | 多智能体 PoC：AgentProfiles（BUSINESS/OPS/FULL）/AgentDispatcher 规则分派/工具子集过滤/**对比评测：双 Agent 胜出**（pass 85.7→100%、幻觉 14.3→0）|
| `e416cf2` | M13 review | **P0+P1**：纯 traceId 分派到 BUSINESS（破坏查日志闭环）+ resume 丢失画像 |
| `7a0f167` | M14 | 评测闭环：100 条分层评测集（20+80）/EvalJudge/LLM-as-judge/BadCaseCollector 回流/阈值校准 |
| `8a17946` | M14 review | **1 P0 + 3 P1**：检查器 traceId 32hex 误报、judge 解析脆弱、badcase id 冲突、断言过严（抽样冒烟 6/8→8/8）|

### 实测数据（本会话）
- **对比评测**（M13，锚点 7 条）：单 Agent pass=85.7%/幻觉=14.3% vs 双 Agent pass=100%/幻觉=0%——**D-A 决策：全量双 Agent**
- **eval-gate 修复后**：pass 57.1-100%、幻觉 0-14.3%——阈值 10/60/40 实测校准保留
- **抽样冒烟**（M14 regression 代表性 8 条）：8/8 通过

---

## 5. 规划（已完成 vs 未来）

### 已完成（roadmap-m10-m14 全部兑现）
```
前置 eval-gate 跑绿 ✓ → M10 会话+记忆 ✓ → M12 工具注册表 ✓ → M11 HITL ✓ → M13 多智能体（PoC 胜出，全量）✓ → M14 评测闭环 ✓
```

### 未来方向（P2 收尾 + 后续增量）
| 项 | 说明 |
|----|------|
| P2 收尾① | EvalJudge 的 Spring 装配（`eval.judge.enabled` 配置项无载体）|
| P2 收尾② | BadCaseCollector 输出路径配置化（jar 部署）|
| nightly | 全量 100 条评测首跑（~2.5h；门禁锚点 20 条不变）+ judge 开启采样 |
| 作品集 | 演示视频（多轮归因/审批闭环/traceId 查询）、300+ 评测集、成本报告、Langfuse |
| M15 模型分层 | 决策冻结维持（单一 flash 足够便宜）|

---

## 6. 深度探索成果（五 Agent 项目）

（同 v4 §6：20 条可借鉴模式按 M10-M14 矩阵——**M10-M14 已按此落地**，吸收率：收件箱双游标/压缩状态机按场景克制未做、其余已采纳。）
**差异化确认**：存在性校验/DECLINE/确定性路由在五项目中不存在——是我们的护城河。

---

## 7. 深度 review 结论（M10-M14 累积）

**每里程碑写前 review（design 修正）+ 写后 review + 二轮 review 已全部入档**（见 §11 文档索引）。按里程碑分类的 P0/P1 修复记录在各 review 文档；方法论沉淀：

1. **迁移黄金规则：委托而非复制**（M12 P0：keyword 白名单漂移）
2. **状态枚举变化须审计所有"终态判断点"**（M11 P1：会话锁/metrics/SSE）
3. **新输入路径/恢复路径须审计既有信号**（M13 P0/P1：traceId 分派、resume 画像）
4. **评测集本身必须被评测**（M14 P0：抽样验证 > 静态断言）
5. **异步时序陷阱**（M10 P1：主线程 UPDATE vs 异步 INSERT）

---

## 8. 已知问题/边界（如实）

| 项 | 说明 |
|----|------|
| 模型发散 | 15 步发散常见（BUDGET_STEPS 兜底）；收敛后单步约 8s；**发散率实测 28.6-42.9%（不在门禁阈值内）** |
| A1/A2 真数据 | 依赖业务流量（事件表 0 行；t_cart_event 316 行）|
| B1 慢查询 | 缺摄入管道（需求单已发）|
| log.search 数据源 | V1 指向生产快照日志（配置化）；实时日志需部署环境挂载 |
| traceId | 仅同步线程（/api/ai/query）；run 异步线程无 traceId（只有 runId）——已知边界 |
| **真库挂起-审批 E2E 不可控** | 模型是否请求 dlq.redeliver 取决于数据（无真实死信时合理不请求）——链路由 fake 单测锁定 |
| **dlq.redeliver 管理通道未配置** | 远端 MQ 不可达（10911 closed）——工具返回 ERROR 如实；生产配 `hitl.dlq-redeliver.url` 即接真实执行 |
| **MCP_API_KEY** | 生产必设（dev 放行 WARN）|
| **评测依赖本地 MCP 服务** | eval-gate/对比评测/抽样冒烟前必须启动 19021（§10）|
| eval-gate | **已跑绿**（前置债清）；阈值 10/60/40 实测校准文档化 |
| judge/badcase | 无 Spring 装配（judge）；路径 cwd 相对（badcase）——P2 |
| 全量 100 条评测 | 未首跑（~2.5h，nightly 项）|
| gateway | 方案已交付待实施（外部）|

---

## 9. 数据现状（2026-08-16 实测）

- my_xhs_ai 库：ai_run **38 行** / ai_step **425 行** / ai_conversation **7 行** / ai_message **20 行** / approval_json **0 条**（无真实审批发生）
- 业务表：t_order **0 行** / t_payment **0 行** / t_cart_event **316 行** / t_note_event 0 行
- 日志快照：22 个中间件日志（config/production-env-config/.../05-logs/）

---

## 10. 如何运行/验证（新会话速查）

```bash
cd /data/workspace/my-xhs
set -a; source .env.local; set +a

# 全量测试（不含真库评测）
mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp

# 真库评测门禁（前置：启动 MCP 19021！）
export MYXHS_LOG_SEARCH_FILES="my-xhs-nacos=/data/workspace/my-xhs/config/production-env-config/05-logs/nacos.log"
nohup java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &   # 19021（先 mvn package）
mvn test -pl my-xhs-ai-app -Peval-gate                                   # 全部 eval-gate tag（4 类 ~55min）
mvn test -pl my-xhs-ai-app -Peval-gate -Dtest=EvalGateRunTest            # 仅门禁锚点 7 条 ~10min

# M13 对比评测（~30min，决策数据）：mvn test -pl my-xhs-ai-app -Peval-gate -Dtest=MultiAgentComparisonTest
# M14 regression 抽样冒烟（~6min）：mvn test -pl my-xhs-ai-app -Peval-gate -Dtest=SampledRegressionTest

# 打包+启动（改动 tools 先 install）
mvn -pl my-xhs-ai-tools install -DskipTests
mvn -pl my-xhs-ai-app,my-xhs-ai-mcp package -DskipTests
java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &   # 19021
java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar &   # 19020
cd frontend && npm run dev                                        # 5173（/ai 页面）

# 真实 E2E（多轮）——一次提交同时拿 runId 与 conversationId：
RESP=$(curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d '{"message":"为什么订单量下降了？"}')
RID=$(echo "$RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['runId'])")
CONV=$(echo "$RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['conversationId'])")
curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d "{\"message\":\"那支付呢？\",\"conversationId\":\"$CONV\"}"          # 第二问承接
curl -s http://127.0.0.1:19020/api/conversations/$CONV                       # 会话详情
curl -sN http://127.0.0.1:19020/api/runs/$RID/stream                          # SSE
# HITL 审批（模型需请求 L3 才挂起；单测已锁定链路）：
curl -s -X POST http://127.0.0.1:19020/api/runs/$RID/approve -H 'Content-Type: application/json' \
     -d '{"decision":"approve","approver":"ops1","reason":"确认"}'

# 清理残留进程
fuser -k 19020/tcp 19021/tcp 5173/tcp
```

> ⚠️ 打包前若改了 tools 模块：先 `mvn -pl my-xhs-ai-tools install` 再打 app/mcp。
> ⚠️ 删真库记录（E2E 后清理）：`mysql -u myxhs_ai_rw -p"$MYXHS_AI_DB_PASSWORD" -e "DELETE FROM my_xhs_ai.ai_step; DELETE FROM my_xhs_ai.ai_run;"`

---

## 11. 文档索引（tech/ 全集）

| 文档 | 内容 |
|------|------|
| `roadmap-m10-m14.md` | 后续规划（**已全部完成**）|
| `design-m10-conversation.md` | M10 设计（多轮/摘要/409）|
| `design-m12-tool-registry.md` | M12 设计（注册表/单一事实源）|
| `design-m11-hitl.md` | M11 设计（挂起/审批/resume 执行）|
| `design-m13-multiagent.md` | M13 设计（双 Agent PoC/对比评测）|
| `design-m14-eval.md` | M14 设计（100 条/judge/badcase/校准）|
| `review-m10-impl.md` | M10 写后 review（2 P0 + 1 P1）|
| `review-m12-impl.md` | M12 写后 review（1 P0 规则漂移）|
| `review-m11-impl.md` | M11 写后+二轮 review（2 P1）|
| `review-m13-impl.md` | M13 写后+二轮 review（P0/P1）|
| `review-m14-impl.md` | M14 写后+二轮 review（P0 + 3 P1）+ **M10-M14 总览表** |
| `deep-plan-v2.md` | 深度规划（Anthropic 业界对照版）|
| `five-agents-insights.md` | 五 Agent 项目探索启示（20 条模式）|
| `review-m10-plan.md` | M10+规划 写码前深度 review |
| `architecture-review-v1.md` / `project-review-v1.md` | 架构/项目 review |
| `architecture-c4.md` | C4 架构图 |
| `gateway-integration.md` | M8-3 gateway 方案 |
| `production-roadmap.md` | M5-M9 规划 + §14 九问证据映射 |
| `ci-eval-gate.md` / `deploy-ai.md` / `red-team-report-v1.md` / `security-supply-chain.md` | 门禁/部署/红队/安全 |
| `my-xhs-ai/docs/INDEX.md` | 17 phase 学习轨 |

---

## 12. 面试/作品集资产

- **九问证据映射**：production-roadmap §14.3；新增弹药：M10 多轮（跨轮数据必须重新查的语义）、M12 注册表（单一事实源）、M11 HITL（挂起=终止+状态标记、审批=resume 注入）、M13 多 Agent（对比评测数据说话：pass +14pt/幻觉归零）、M14 评测闭环（评测集本身被评测）
- **叙事升级**："我按 Anthropic 模式构建了 Agent 平台，并知道每一步为什么"——Routing/HITL checkpoint pause/Orchestrator-workers 克制演进/Evaluator 模式均有对照 + **评测数据支撑**
- **方法论资产**：每个里程碑的 design→写前 review→小步实现→写后 review→二轮 review 闭环（5 条方法论沉淀见 §7）
- **作品集缺口**：C4 图 ✅；剩余：演示视频、300+ 评测集、Langfuse、成本报告（P2 收尾后）

---

## 13. 交接检查单（新会话开始前）

- [ ] 通读本文件（v5）+ `roadmap-m10-m14.md`（完成态）+ 最近 review 文档（`review-m14-impl.md`）
- [ ] 确认代码可用：`ls my-xhs-ai-app/src my-xhs-ai-mcp/src my-xhs-ai-tools/src frontend/src/pages/ai`
- [ ] `source .env.local`（凭据就绪；成本红线仅 flash）
- [ ] 白名单 `MYXHS_LOG_SEARCH_FILES`（log.search 依赖；评测也依赖）
- [ ] 遵守纪律：**写码前先深度 review**；每单元小步 + 评测安全网；**评测前启动 MCP**
- [ ] 改动 tools 模块记得 install
- [ ] 提交 git 前确认 `.env.local` 未跟踪（`git check-ignore .env.local`）
- [ ] 密钥/凭据绝不落库、不入 git、不进文档
- [ ] **下一步执行顺序**：① P2 收尾（judge 装配/badcase 路径）→ ② nightly 全量评测首跑（MCP 先行）→ ③ 演示视频/作品集
