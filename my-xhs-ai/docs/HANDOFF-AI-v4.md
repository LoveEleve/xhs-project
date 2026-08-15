# HANDOFF-AI v4（my-xhs-ai 交接文档·详细版）

> 日期：2026-08-15 | 用途：**新会话/新上下文无缝接续**。本文件是最详细版本（v3 之后新增：路由重构/traceId 查询/历史追溯/架构重构/M10-M14 规划/五项目探索/深度 review）。
> 所有路径相对 `/data/workspace/my-xhs`。取代 v3（v3/v2 保留仅作历史）。
> 通读约 20 分钟。配套文档索引见 §11。

---

## 0. 项目一句话与当前状态

> 给 my-xhs 电商平台建**运营/运维诊断 AI Agent**：查订单/支付/内容/系统指标，多步归因，带证据链、可追溯、不越权、不编造。
> **功能主线已完结 + 架构已重构 + 深度 review 全清；进入 M10-M14 规划执行阶段**。
> **192 个 @Test 全绿**（tools 46 + app 135 + mcp 11，其中 6 个集成测试无凭据自动跳过）。
> **下一步（顺序已定）**：① eval-gate 跑绿（前置债）→ ② M10 会话与记忆动工。

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
| `MYXHS_AI_DB_USER/PASSWORD` | AI 自有库写账号 `myxhs_ai_rw`（my_xhs_ai 库）| |
| `MYXHS_ES_PASS` | ES elastic 认证 | |
| `ARK_PLAN_API_KEY/BASE_URL/EMBEDDING_MODEL` | RAG embedding + **语义路由降级 embedding**（doubao-embedding-vision-large 2048 维，**单批 ≤10 条**）| |
| `MCP_API_KEY` | MCP 认证（dev 未设放行 WARN，生产必设）| |
| `MYXHS_LOG_SEARCH_FILES` | log.search 白名单（service=日志文件绝对路径，CSV）| **不配则 log.search 全拒** |

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

---

## 2. 代码结构与端口

### 模块
| 模块 | 端口 | 规模(main/test) | 职责 |
|------|:--:|:--:|------|
| `my-xhs-ai-tools` | — | 1344/802 | 共享工具纯类：业务×3、事件流水×3、baseline、观测×6、**log.search**、ToolJson/窗口/白名单 |
| `my-xhs-ai-app` | 19020 | 5093/3304 | AI 核心：路由/Harness/RunManager/RunStore/评测/指标/SSE |
| `my-xhs-ai-mcp` | 19021 | 438/306 | MCP 服务：**14 工具**（Streamable HTTP，认证+审计）|
| `frontend/` | 5173(dev) | pages/ai 659 | AI 诊断台薄壳（SSE 实时/证据链 Drawer/取消/?run= 直开/?conv= 规划）|

### 关键类（app 模块）
| 类 | 职责 |
|----|------|
| `config/RouterConfig` | **三阶路由装配**：L0 规则 → L1 **LLM 分类（默认开）** → L2 语义降级 → L3 默认引导 |
| `config/HarnessConfig` | AgentHarness 装配（预算/单价/截断长度/模型名/logSearch）|
| `config/LogSearchAccessConfig` | 受控日志检索装配（mcp/direct 双模式，白名单 CSV）|
| `service/router/IntentRouter` | 三阶路由（L0 指标/归因/ID 强信号 → L1 LLM 分类主路径 → L2 语义 → L3 引导）|
| `service/router/LlmIntentClassifierImpl` | LLM 分类（全意图+理由+容错解析；失败→null 降级不保守 AGENT）|
| `service/router/SemanticIntentClassifier` | 语义降级（49 种子 few-shot + cosine + LRU + 429 重试）|
| `service/embedding/EmbeddingClient` | 共享 embedding 客户端（RAG + 语义路由；单批≤10 + 429 重试）|
| `service/agent/harness/AgentHarness` | **状态机**：executeLoop 调度（70 行）+ handleToolCall/Decline/Answer（拆分后）|
| `service/agent/harness/HarnessEventType` | 事件枚举（契约类型化）|
| `service/agent/harness/StepState` | Step 状态枚举（持久化 name()，兼容旧数据）|
| `service/run/RunManager` | 事件缓冲/单消费者/cancelStream（token 先登记闭合竞态）/崩溃恢复/直答落库 |
| `service/store/JdbcRunStore` | Run Store（checkpoint/心跳/claimRunning/**finalAnswer 落库**）|
| `eval/*` | EvalCase/EvalRunner/EvalGate（门禁阈值 10/60/40 拍脑袋，**待校准**）|

### 端点（app:19020）
| 端点 | 说明 |
|------|------|
| `POST /api/runs` | 提交诊断（返回 runId；问候/超范围直答也落库）|
| `GET /api/runs/{id}` | 状态/步骤/答案（**内存 miss 回退 Run Store**，fromStore 标记）|
| `DELETE /api/runs/{id}` | 协作式取消 |
| `GET /api/runs/{id}/stream` | SSE 订阅（单消费者 409；断开立即释放）|
| `POST /api/ai/query` | 路由直答（指标确定性/问候/超范围直答带 runId/AGENT 模型调用）|
| `POST /api/ai/chat` `/chat/stream` | D1 保留 |
| `POST /api/ai/rag/**` | RAG 系列 |
| `GET /actuator/prometheus` | M6-3 指标 |

### MCP 工具（mcp:19021，14 个）
`order.query_volume` `payment.success_rate` `content.interaction` `baseline.window` `funnel.conversion` `payment.failures` `content.publish_events` **`log.search`** `service.http_errors` `service.http_latency` `mq.consumer_lag` `mq.dlq_backlog` `mysql.replication_lag` `mysql.deadlocks`

### 配置项（application.yml，myxhs.ai.*）
`llm.timeout-seconds` / `router.llm-fallback.enabled`(默认 true) / `router.semantic-threshold`(0.42) / `agent.max-steps|max-tokens|max-cost|price-per-1k-tokens|max-invalid-answers|tool-result-max-len` / `tools.mode`(mcp/direct) / `rag.*` / **`log-search.files`** / `eval.gate.*`

---

## 3. 架构（重构后）

```
前端薄壳 → POST /api/runs（convId 规划中）
  → 三阶意图路由（L0 规则/L1 LLM 分类主路径/L2 语义降级/L3 默认引导）
  → RunManager（事件缓冲/单消费者/cancelStream/崩溃恢复/直答落库）
  → AgentHarness（executeLoop 调度 + handler 拆分）
      → PolicyGuard（deny-by-default allowlist + 参数白名单 + L3 恒拒绝）
      → MCP 14 工具（真实数据源）
      → 存在性校验（registry）+ DECLINE 零证据豁免
  → SSE 事件流（HarnessEventType 枚举契约）
横切：Run Store（checkpoint/心跳/finalAnswer）、RunMetrics、traceId（仅同步线程）、MCP 认证+审计
```

### 核心机制（面试/写作必讲）
1. **三阶意图路由**：L0 只保"必须确定性"（指标词数字可重复/归因词明确信号/traceId 32 位 hex 强信号）；**其余语义理解交给 LLM 分类**（主路径，默认开）——人工枚举"理解"是错误架构，LLM 负责理解、规则只保确定性
2. **存在性校验**：ANSWER 的 evidenceRefs 必须命中 ToolResultRegistry（本轮 run 级），防编造
3. **DECLINE 零证据豁免**：拒答/超范围明确说明即可，不被校验逼着"凑证据"（如"天气→查主从延迟"类反噬修复）
4. **受控工具**：log.search 白名单文件 map 严格查找（无路径拼接）+ keyword 字符白名单 + 4 重截断 + 纯 Java 读文件（无 shell）
5. **历史追溯**：ai_run 有 final_answer 列；内存 miss → store 回退重建视图（fromStore）；全量 run（含直答）落库
6. **崩溃恢复**：checkpoint + 心跳 10min + claimRunning 原子认领 + 重放（仅 THINK 计步）
7. **事件契约**：HarnessEventType/StepState 枚举化；工具输出 ToolJson 类型化（record 序列化）

---

## 4. 已交付里程碑（含重构）

### D1-D4（核心引擎）
D1 三真实指标工具 + 路由（后升级三阶）；D2 MCP 服务 + 自研薄协议客户端；D3 RAG（ES BM25/dense/hybrid RRF）；D4 AgentHarness 全状态机（预算/循环检测/存在性校验/证据链/确定性窗口注入）

### M5-M9
M5 Durable（Run Store/异步化/取消/崩溃恢复）；M6 评测门禁（21 条 9 层 + EvalGate）；M7 安全（红队 3/3 + SBOM + 密钥零泄漏）；M8（容器化/演练/UI 薄壳/gateway 方案）；M9-1 受控日志检索（log.search）

### 2026-08-15 会话（重要新增）
| commit | 内容 |
|--------|------|
| `3000170` | **DECLINE 零证据豁免**（拒答不再被校验逼着凑证据）|
| `360c613`+`9db700a`+`043257d` | 意图分类边界探针 + 默认值反转（无信号引导）|
| `cc9ad6b` | **语义路由**（embedding few-shot 取代闲聊词表穷举）|
| `b7f00dc` | **路由主次反转**：LLM 负责理解，规则只保确定性（架构级）|
| `25cc5f8` | **log.search**（M9-1 受控日志检索）|
| `6c605a0` | **traceId 查询闭环**（输入 32 位 hex → AGENT → logSearch 查日志）|
| `7d1a345`+`e602dcc` | **历史 run 可追溯**（store 回退 + finalAnswer 落库 + 直答落库）|
| `b0af0d2` | **架构重构第一批**：事件枚举化 + executeLoop 拆分 + 工具输出类型化 |
| `d2f8f40` | **P0 修复**：RunManager 预检与 AiQueryController 路由不一致 |
| `91fbb47` | 深度 review P1/P2 全部修复 |

### 故障演练（实测）
模型限流（timeout=2s → FAILED/MODEL_UNAVAILABLE 零编造）；MySQL 宕（mcp 指向坏库 → 工具 ERROR 回填 + 交叉验证 + 不确定性）

---

## 5. 规划（M10-M14，重点）

### 文档
- `roadmap-m10-m14.md`：范围 + 执行顺序 + 验收门禁 + 决策点（已拍板）
- `design-m10-conversation.md`：**M10 详细设计（深度 review 修订版，可开工）**
- `deep-plan-v2.md`：业界对照（Anthropic 五种模式映射）
- `five-agents-insights.md`：五 Agent 项目探索启示（20 条模式矩阵）
- `review-m10-plan.md`：**写码前深度 review（2 个 P0 已修 + 借鉴克制）**

### 里程碑
```
前置：eval-gate 跑绿（7 个失败用例修复）
M10 会话+记忆 → M12 工具注册表(含 accessLevel) → M11 HITL(挂注册表) → M13 多智能体 → M14 评测闭环
```

### 决策点（已拍板）
| # | 决策 | 结论 |
|---|------|------|
| D-A | 多智能体 | **PoC 后定**：主 Agent + 审查 fork 最小形态（hermes 参考）→ 评测对比 |
| D-B | 记忆范围 | **会话级先做**（摘要记忆），用户级为后续增量 |
| D-C | HITL 首工具 | **mq 死信重投 `dlq.redeliver`**（命令模板写死+参数白名单）|
| D-D | 模型分层 | **继续冻结** |

### M10 设计要点（修订版）
- 历史注入 = user/assistant 结论消息（**无工具原文**——跨轮证据校验冲突 P0-1 修复；想用上轮数据必须重新调用工具，正确语义）
- 职责分离：ai_message 会话专用 / checkpoint 恢复专用（P0-2）
- 20 轮截断 + 规则摘要（V1 不做压缩状态机——诊断短会话，场景适配）
- 同会话并发 409 限制
- DDL：ai_conversation / ai_message（见 design-m10-conversation.md §2）

### 前置债
**eval-gate 跑绿**（上次 7/7 失败 passRate 0.0 未追查）——所有里程碑验收的地基。

---

## 6. 深度探索成果（五 Agent 项目）

本地源码 + 60+ 分析文档两路深挖（`five-agents-insights.md`），20 条可借鉴模式按 M10-M14 矩阵：

| 能力域 | 借鉴模式（来源）|
|--------|------|
| M10 会话/记忆 | 收件箱双游标(opencode，**V1 不做**)/三段式压缩+保留尾(pi，**简化**)/防thrash+提交栅栏(hermes，**简化**)/Session Facts表(pi)/双态快照+subject冲突(reasonix)/Context Epoch(opencode)|
| M11 HITL | 五事件工具管线+fail-closed(deepseek-harness)/组合预设权限(pi)/变异屏障(reasonix)|
| M12 注册表 | Capability Seams 三角色(deepseek-harness)/definition-first(pi)|
| M13 多 Agent | 审查 fork 最小形态(hermes)/权限继承+防递归(opencode)/每key串行跨key并行(opencode)|
| M14 评测 | TaskSpec×evidence_ids 验收(reasonix，**与证据链互相印证**)/eval harness 真实会话(pi)/无人值守三件套(reasonix)|
| 横切 | 预算 consume/refund+grace call+中断占位(hermes)/profile-bundle 版本化(deepseek-harness，暂缓)|

**差异化确认**：存在性校验/DECLINE/确定性路由在五项目中不存在——是我们的护城河。

---

## 7. 深度 review 结论

### 已修复（架构 review v1 + M10 review）
- P0：RunManager 预检与 AiQueryController 路由不一致（两入口行为分裂）
- P1×3：cancelStream 窗口竞态（token 先登记）/ resume 指标失真 / 直答落库失败可见
- P2×3：StepState 枚举化 / /api/ai/query 直答落库 / ToolJson.error 带 window
- M10 设计 P0×2：跨轮证据校验冲突（方案 A）/ checkpoint 与会话双写职责
- 顺序修正：M12 提前到 M11 前；eval-gate 跑绿为前置

### 待办（低危，记录在案）
- P2-4 purgeDone 内联（低频无影响）、P2-5 resume query 兜底
- 收敛性：模型发散常见（BUDGET_STEPS 兜底）
- 数据：业务表 t_order/t_payment 0 行（t_cart_event 316 行）——系统真实、数据演示级

---

## 8. 已知问题/边界（如实）

| 项 | 说明 |
|----|------|
| 模型发散 | 15 步发散常见（BUDGET_STEPS 兜底）；收敛后单步约 8s |
| A1/A2 真数据 | 依赖业务流量（事件表当前 0 行；t_cart_event 有 316 行）|
| B1 慢查询 | 缺摄入管道（需求单已发）|
| log.search 数据源 | V1 指向生产快照日志（配置化）；实时日志需部署环境挂载 |
| traceId | 仅同步线程（/api/ai/query）；run 异步线程无 traceId（只有 runId）——已知边界 |
| eval-gate | 从未真跑绿（7/7 失败未追查）——**前置债** |
| MCP_API_KEY | 生产必设（dev 放行 WARN）|
| gateway | 方案已交付待实施（外部）|

---

## 9. 数据现状（2026-08-15 实测）

- my_xhs_ai 库：ai_run **28 行** / ai_step **246 行**（真实运行记录）
- 业务表：t_order **0 行** / t_payment **0 行** / t_cart_event **316 行** / t_note_event 0 行
- 日志快照：22 个中间件日志（config/production-env-config/.../05-logs/）

---

## 10. 如何运行/验证（新会话速查）

```bash
cd /data/workspace/my-xhs
set -a; source .env.local; set +a

# 全量测试（不含真库评测）
mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp

# 真库评测门禁（前置债：先跑绿）
mvn test -pl my-xhs-ai-app -Peval-gate

# 打包+启动（改动 tools 先 install）
mvn -pl my-xhs-ai-tools install -DskipTests
mvn -pl my-xhs-ai-app,my-xhs-ai-mcp package -DskipTests
export MYXHS_LOG_SEARCH_FILES="my-xhs-nacos=/绝对路径/nacos.log,my-xhs-ai-app=/绝对路径/app.log,..."  # 白名单必配
java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &   # 19021
java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar &   # 19020
cd frontend && npm run dev                                        # 5173（/ai 页面）

# 真实 E2E
RID=$(curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d '{"message":"为什么订单量下降了？"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['runId'])")
curl -s http://127.0.0.1:19020/api/runs/$RID          # 轮询至终态
curl -sN http://127.0.0.1:19020/api/runs/$RID/stream  # SSE 订阅
# traceId 查询闭环：
TR=$(curl -s -X POST http://127.0.0.1:19020/api/ai/query -H 'Content-Type: application/json' -d '{"message":"帮我查下支付成功率"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['traceId'])")
curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' -d "{\"message\":\"$TR\"}"  # 输入 traceId → Agent 查日志
# 直答（零成本）：问候/超范围
curl -s -X POST http://127.0.0.1:19020/api/ai/query -H 'Content-Type: application/json' -d '{"message":"你好"}'

# 清理残留进程
fuser -k 19020/tcp 19021/tcp 5173/tcp
```

> ⚠️ 打包前若改了 tools 模块：先 `mvn -pl my-xhs-ai-tools install` 再打 app/mcp。
> ⚠️ 删真库记录（E2E 后清理）：`mysql -u myxhs_ai_rw -p"$MYXHS_AI_DB_PASSWORD" -e "DELETE FROM my_xhs_ai.ai_step; DELETE FROM my_xhs_ai.ai_run;"`

---

## 11. 文档索引（tech/ 全集）

| 文档 | 内容 |
|------|------|
| `roadmap-m10-m14.md` | **后续规划**（范围/顺序/验收/决策点）|
| `design-m10-conversation.md` | **M10 详细设计（可开工）** |
| `deep-plan-v2.md` | 深度规划（Anthropic 业界对照版）|
| `five-agents-insights.md` | 五 Agent 项目探索启示（20 条模式）|
| `review-m10-plan.md` | M10+规划 写码前深度 review |
| `current-state-v1.md` | 现状梳理快照 |
| `architecture-review-v1.md` | 架构深度 review（P0-P2 修复记录）|
| `project-review-v1.md` | 项目整体 review（三空结论）|
| `architecture-c4.md` | C4 架构图（容器/组件/时序）|
| `gateway-integration.md` | M8-3 gateway 方案（交付物）|
| `production-roadmap.md` | M5-M9 规划 + §14 九问证据映射 |
| `design-agent-harness.md` | Harness 详细设计（HITL §5 参考）|
| `ci-eval-gate.md` / `deploy-ai.md` / `red-team-report-v1.md` / `security-supply-chain.md` | 门禁/部署/红队/安全 |
| `my-xhs-ai/docs/INDEX.md` | 17 phase 学习轨 |

---

## 12. 面试/作品集资产

- **九问证据映射**：production-roadmap §14.3（可当场复现）；新增弹药：三阶路由（LLM 理解 vs 确定性分层）、DECLINE（校验反噬修复）、受控工具（无 shell）、历史追溯、五项目对照（业界模式印证）
- **叙事升级**："我按 Anthropic 模式构建了 Agent 平台，并知道每一步为什么"——Routing/HITL checkpoint pause/Orchestrator-workers 克制演进/Evaluator 模式均有对照
- **作品集缺口**：C4 图 ✅；剩余：eval-gate 跑绿、演示视频、300+ 评测集、Langfuse、成本报告、多 Agent PoC

---

## 13. 交接检查单（新会话开始前）

- [ ] 通读本文件（v4）+ `roadmap-m10-m14.md` + `design-m10-conversation.md`
- [ ] 确认代码可用：`ls my-xhs-ai-app/src my-xhs-ai-mcp/src my-xhs-ai-tools/src frontend/src/pages/ai`
- [ ] `source .env.local`（凭据就绪；成本红线仅 flash）
- [ ] 白名单 `MYXHS_LOG_SEARCH_FILES`（log.search 依赖）
- [ ] 遵守纪律：**写码前先深度 review**（本轮多次教训）；每单元小步 + 评测安全网
- [ ] 改动 tools 模块记得 install
- [ ] 提交 git 前确认 `.env.local` 未跟踪（`git check-ignore .env.local`）
- [ ] 密钥/凭据绝不落库、不入 git、不进文档
- [ ] **下一步执行顺序**：① eval-gate 跑绿（前置债）→ ② M10 动工（按修订后设计）
