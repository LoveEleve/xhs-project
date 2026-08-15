# HANDOFF-AI v3（my-xhs-ai 交接文档·完结版）

> 日期：2026-08-15 | 用途：**新会话/新上下文无缝接续**。通读本文件（约 15 分钟）即可接续。
> 所有路径相对 `/data/workspace/my-xhs`。取代 v2（v2 保留仅作历史）。
> 配套：`my-xhs-ai/business-analysis/tech/architecture-c4.md`（C4 图）、`production-roadmap.md`（规划）、`gateway-integration.md`（M8-3 方案）、`HANDOFF-AI-v2.md`（历史）。

---

## 0. 项目一句话与当前状态

> 给 my-xhs 电商平台建**运营/运维诊断 AI Agent**：查订单/支付/内容/系统指标，多步归因，带证据链、可追溯、不越权、不编造。
> **状态：项目主线完结**——D1-D4 核心 + M5（Durable）+ M6（评测门禁）+ M7（安全实证）+ M8（容器化/演练/UI 薄壳）+ M9-1（受控日志检索）已完成。
> **189 个 @Test 全绿**（tools 46 + app 132 + mcp 11，集成测试无凭据自动跳过）。
> **下一步**：M8-3 gateway 实施（外部依赖，方案已交付 gateway-integration.md）；面试资产补全（见 §12）。

---

## 1. 环境与凭据（安全红线）

### 目录
| 路径 | 说明 |
|------|------|
| `/data/workspace/my-xhs` | 唯一 git 仓库（remote: `LoveEleve/xhs-project.git`，分支 main）|
| `my-xhs-ai/` | 规划（business-analysis）+ 学习轨（docs）+ 详细设计（tech）|
| `my-xhs-ai-app/` `my-xhs-ai-mcp/` `my-xhs-ai-tools/` | 三个代码模块（已全部提交 git）|
| `frontend/` | 前端（M8-4 AI 诊断台薄壳页面在 frontend/src/pages/ai/）|
| `/data/workspace/my-xhs/.env.local` | 凭据唯一来源（gitignored，已确认 check-ignore OK）|
| `my-xhs-ai/business-analysis/tech/` | design-agent-harness / production-roadmap / deploy-ai / ci-eval-gate / red-team-report-v1 / security-supply-chain / gateway-integration / architecture-c4 |

### 凭据（`.env.local` 变量，绝不落库/不入 git）
| 变量 | 用途 | 备注 |
|------|------|------|
| `MYXHS_LLM_API_KEY` | **主模型 OpenCode Go**（`opencode.ai/zen/go/v1`，deepseek-v4-flash）| **成本红线：仅 deepseek-v4-flash，禁止多模型** |
| `TEAMO_API_KEY` | 旧 TeamoRouter key（已不用）| |
| `MYXHS_DB_USER/PASSWORD` | 只读账号 `myxhs_ai_ro`（SELECT）| 真实 MySQL |
| `MYXHS_ROOT_PASSWORD` | 集成测试自愈 seed + DDL（root）| 仅测试/建表 |
| `MYXHS_AI_DB_USER/PASSWORD` | AI 自有库写账号 `myxhs_ai_rw`（my_xhs_ai 库）| |
| `MYXHS_ES_PASS` | ES elastic 认证 | |
| `ARK_PLAN_API_KEY/BASE_URL/EMBEDDING_MODEL` | RAG embedding + **语义路由 embedding**（doubao-embedding-vision-large 2048 维）| |
| `MCP_API_KEY` | MCP 认证（dev 未设放行 WARN，生产必设）| |

### 基础设施（真实，远端）
| 服务 | 地址 | 备注 |
|------|------|------|
| MySQL | `21.130.247.89:3306`（orders 分片 ds0..3×t_order_0..3；事件表 ×4 已建）| 只读 myxhs_ai_ro / 写 myxhs_ai_rw |
| ES | `21.130.247.89:19200`（8.19.19）| RAG |
| Prometheus | `21.130.247.89:19090` | 观测数据源 |
| SkyWalking | `21.130.247.89:12800` | 跨服务链路（备用）|
| 模型 | OpenCode Go `opencode.ai/zen/go/v1` | Bearer MYXHS_LLM_API_KEY |
| Embedding | 火山 `ark.cn-beijing.volces.com/api/plan/v3` | Bearer ARK_PLAN_API_KEY，**单批 ≤10 条** |

> ⚠️ 本地无 docker/MySQL（构建/DB 验证留部署环境/远端）。**git 提交前必查 `.env.local` 未被跟踪**。

---

## 2. 代码结构与端口

### 模块
| 模块 | 端口 | 职责 |
|------|:--:|------|
| `my-xhs-ai-tools` | — | 共享真实工具纯类：业务（Order/Payment/Content/Baseline/Event）+ 观测（PrometheusQuery）+ **LogSearch（M9-1 受控日志检索）** |
| `my-xhs-ai-app` | 19020 | AI 核心：意图路由（三阶）/AgentHarness/RunManager/Run Store/评测/指标/SSE |
| `my-xhs-ai-mcp` | 19021 | MCP 工具服务（Streamable HTTP `/mcp`，认证+审计；**14 个工具**）|
| `frontend/` | 5173(dev) | React19+antd6；`/ai` AI 诊断台（薄壳）+ `/ai-api` dev 代理→19020 |

### 关键类（app）
| 类 | 职责 |
|----|------|
| `config/RouterConfig` | 三阶路由装配（语义分类器 + LLM 兜底默认关）|
| `config/HarnessConfig` | AgentHarness 装配（预算/单价/截断长度/模型名）|
| `config/LogSearchAccessConfig` | 受控日志检索装配（mcp/direct 双模式，白名单 CSV）|
| `service/router/IntentRouter` | **三阶意图路由**：L0 规则（诊断封闭集）→ L1 语义（embedding few-shot）→ L2 LLM → L3 默认引导 |
| `service/router/SemanticIntentClassifier` | 49 条种子示例 cosine 分类；种子懒加载缓存 + 查询 LRU；429 退避重试（EmbeddingClient）|
| `service/embedding/EmbeddingClient` | 共享 embedding 客户端（RAG + 语义路由；单批≤10 + 429 重试）|
| `service/agent/harness/*` | D4 核心：AgentHarness（状态机 + **DECLINE 零证据豁免**）/PolicyGuard/LoopCtrl/ToolResultRegistry/EvidenceChain |
| `service/run/RunManager` | M5 异步化：事件缓冲/单消费者/**cancelStream（客户端断开释放）**/崩溃恢复 |
| `service/store/JdbcRunStore` | Run Store（checkpoint/心跳/claimRunning）|
| `eval/*` | M6 评测：EvalCase/EvalRunner/EvalGate（PR 门禁阈值）|

### 端点（app:19020）与 MCP 工具（mcp:19021，14 个）
- 端点：`/api/runs`（POST 提交/GET 查询/DELETE 取消/`/{id}/stream` SSE）、`/api/ai/query`（路由直答）、`/api/ai/chat`、`/api/ai/rag/**`、`/actuator/prometheus`
- 工具：`order.query_volume` `payment.success_rate` `content.interaction` `baseline.window` `funnel.conversion` `payment.failures` `content.publish_events` **`log.search`（M9-1）** `service.http_errors` `service.http_latency` `mq.consumer_lag` `mq.dlq_backlog` `mysql.replication_lag` `mysql.deadlocks`

---

## 3. 架构摘要（C4 图见 architecture-c4.md）

```
用户 → 前端 /ai（薄壳）→ POST /api/runs → RunManager（事件缓冲）
  ├─ 意图预检：三阶路由（L0 规则/L1 语义/L2 LLM/L3 默认）——问候/超范围零成本直答
  ├─ AgentHarness 状态机：THINK(LLM JSON 决策) → VALIDATE(PolicyGuard deny-by-default)
  │    → TOOL(MCP 14 工具→真实 MySQL/Prometheus/日志文件) → 存在性校验 → LOOPCHECK(预算三重封顶)
  │    → DECLINE（拒答零证据豁免，防"凑证据"编造）
  ├─ SSE 推送（RUN_STARTED→THINK/TOOL/ANSWER→COMPLETED/PARTIAL/FAILED/CANCELLED）
  └─ 横切：Run Store checkpoint、RunMetrics、traceId、只读账号、MCP 认证+审计、评测门禁
```

**核心原则**（写码必守）：
1. 模型只能调固定工具（allowlist），永不执行任意代码/SQL/PromQL（CodeAct 红线）
2. 数字必须来自工具结果（存在性校验），模型编造是红线
3. **成本红线：LLM 仅 deepseek-v4-flash**，禁止多模型
4. 每步改动必须有评测安全网（M6 门禁）

---

## 4. 已交付里程碑（含完结项）

### D1-D4（核心引擎）
- D1 三真实指标工具 + IntentRouter 混合路由（后升级三阶）
- D2 MCP 服务（SDK 0.18.3）+ 自研薄协议客户端
- D3 RAG（ES BM25/dense/hybrid RRF；回答带引用）
- D4 AgentHarness 全状态机（预算/循环检测/存在性校验/证据链/确定性窗口注入）

### M5 Durable
Run Store（checkpoint/心跳）→ 异步化（/api/runs + SSE 单消费者）→ 协作式取消 → 崩溃恢复（claimRunning 原子认领）

### M6 评测与可观测
YAML 评测集（21 条）+ 分层断言（幻觉检测）→ ToolUsageAnalyzer（发散检测）→ /actuator/prometheus → EvalGate PR 门禁（-Peval-gate profile）

### M7 安全实证
红队用例 3/3 拦截（注入/越权/PII）→ SBOM（63 组件）+ 密钥零泄漏 → **M9-1 log.search 受控检索**（白名单文件/无 shell/参数白名单）

### M8 部署（完结）
- M8-1 容器化（独立 Dockerfile + compose 片段）
- M8-2 故障演练：MCP 不可用/模型不可用 ✅
- M8-3 gateway 接入：**方案已交付**（gateway-integration.md，待 gateway 团队实施）
- M8-4 UI 薄壳 ✅（AI 诊断台：SSE 实时/证据链可点/取消/?run= 直开）

### M9-1 受控进程工具（新完结项）
`log.search`：白名单 service→文件严格 map（防路径遍历）+ keyword 字符白名单 + 4 重截断 + 纯 Java 读文件（无 shell）
- 实测：查 Nacos 日志→9 条 ERROR 归因（Application run failed）+ 证据链/不确定性；查白名单外服务→拒绝且如实说明+建议替代工具

### 2026-08-15 会话新增（意图/对话体验层）
- **三阶意图路由**：语义路由（embedding few-shot）取代闲聊词表穷举；无诊断信号默认引导
- **DECLINE 零证据豁免**：拒答不再被存在性校验逼着"凑证据"（如"天气→查主从延迟"类）
- **问候/超范围直答**：零模型/工具成本（costMs=0）
- 边界回归集固化（40+ 条断言：问候/超范围/归因优先不误伤/语义种子外变体）

### 故障演练补项（2026-08-15 实测）
| 演练 | 做法 | 结果 |
|------|------|------|
| 模型限流/超时 | `--myxhs.ai.llm.timeout-seconds=2` | FAILED/MODEL_UNAVAILABLE，明确降级文案，0 步骤 0 编造（重试 1 次日志确认）|
| MySQL 宕 | mcp 指向不可达 DB（连接超时 2s）| 工具 ERROR 回填，Agent 如实报告查询失败零编造，交叉验证 5xx/主从/死锁/日志后给推断+不确定性 |

### 测试
**189 个 @Test 全绿**（tools 46 + app 132 + mcp 11）。集成测试（真库/真 embedding/真模型）无凭据自动跳过。**测试数随演进变化，以最新 `mvn test` 为准**。

---

## 5. 关键决策与红线

| 决策 | 内容 |
|------|------|
| 成本红线 | **LLM 仅 deepseek-v4-flash（OpenCode Go）**，禁止多模型 |
| 意图路由 | **三阶**：L0 规则（诊断封闭集，零成本确定性）→ L1 语义（embedding few-shot，语义分类）→ L2 LLM（默认关）→ L3 默认引导；诊断侧才穷举词表，闲聊侧不穷举 |
| 拒答机制 | DECLINE 动作零证据豁免（防存在性校验反噬逼模型凑证据）|
| 日志检索 | M9-1 受控进程工具：白名单文件 + 无 shell + 4 重截断；服务白名单配置 `myxhs.ai.log-search.files` |
| MCP 版本 | 0.18.3（Spring 集成模块未达 2.0 GA；2.0 后升级）|
| 工具访问 | `myxhs.ai.tools.mode=mcp`(默认)/direct |
| 评测阈值 | 幻觉≤10%/通过≥60%/完成≥40%（保守，校准后收紧）|
| 崩溃恢复 | 心跳 10min 超时 + claimRunning 原子认领 |
| SSE | 单消费者（并发 409）；客户端断开 cancelStream 立即释放（M8-4 修复）|

---

## 6. 深度 review 教训（防重踩）

1. 免费模型工具调用不可靠会虚构 → 付费 flash + 存在性校验（确定性兜底）
2. 模型发散是常态（BUDGET_STEPS 反复出现）→ prompt 收敛压力 + 发散率监控
3. 报告要用实测验证（多次文档乐观/过时教训）
4. 拒绝性回答会提及工具名（红队断言查"成功语义"而非"不含工具名"）
5. Map.of 禁止 null 值（view 序列化 NPE——已改 HashMap）
6. Jackson 把 isXxx() 序列化成属性（@JsonIgnore + ignoreUnknown）
7. 重放计步语义（崩溃恢复只 THINK 计步）
8. 评测测试 api-key 占位符 `${MYXHS_LLM_API_KEY:test-key}`（TestPropertySource 优先级>env）
9. **意图分类不能靠词表穷举**（开放集无底洞）→ 语义路由 + 默认引导反转
10. **存在性校验会反噬**：无 DECLINE 时模型被迫调无关工具凑证据（"天气→查主从延迟"）→ 拒答零证据豁免
11. **embedding API 限制**：单批 ≤10 条（49 条种子 400 报错）、429 限流（退避重试 + LRU 缓存）
12. surefire 下 actuator 端点 404 为环境差异（测试用 MeterRegistry）
13. eval-gate 门禁单位语义（指标是百分比，阈值也按百分比）
14. YAML 顶层键重复会 DuplicateKeyException（合并进现有 myxhs: 块）

---

## 7. 已知问题/边界（如实）

| 项 | 说明 |
|----|------|
| 模型发散 | 15 步发散常见（BUDGET_STEPS 兜底）；收敛后单步约 8s |
| A1/A2 真数据 | 依赖业务流量（事件表当前 0 行；工具链路已闭环）|
| B1 慢查询 | 缺摄入管道（需求单已发，运维建）|
| log.search 数据源 | V1 指向生产快照日志（配置化 MYXHS_LOG_SEARCH_FILES）；实时日志需部署环境挂载 |
| MCP_API_KEY 生产必设 | dev 未设放行（WARN）|
| gateway /api/ai/** | 方案已交付（gateway-integration.md），待 gateway 团队实施 |
| traceId 单服务 / 审计应用日志 | 跨服务 OTel = D6 深化 |
| 记忆（长期上下文）| M9 未做（决策点：价值 vs 复杂度）|
| Langfuse/Temporal PoC | 未做（决策点 D1/D2）|
| 评测集规模 | 21 条（作品集目标 300+，可扩展）|

---

## 8. 剩余工作（完结后）

### 外部依赖（对方配合项）
| 项 | 归属 | 状态 |
|----|------|------|
| gateway /api/ai/** 路由+角色 | gateway 团队 | **方案就绪待实施** |
| 慢查询摄入管道（B1） | 运维 | 需求单已发 |
| MCP_API_KEY 生产设置 | 部署 | 未定（生产必设）|
| 只读账号白名单/轮换 | DBA/运维 | 建议已提 |
| 观测端点认证（iptables 持久化）| 运维 | 建议已提 |
| 部署环境（docker/云主机）| 运维 | 未定（本地无 docker）|
| DLQ -1 哨兵值（应用侧改代码）| 微服务侧 | 已反馈 |

### 项目侧可做
- M9 深化：记忆、模型分层 routing（决策点 D4 冻结）、B1/A1/A2 闭环（等外部）
- 阈值校准（真库评测几轮后收紧）

### 面试/作品集（§12 缺口）
- C4 图 ✅ 已完成（architecture-c4.md）；演示视频、成本报告、300+ 评测集待补

---

## 9. 如何运行/验证（新会话速查）

```bash
cd /data/workspace/my-xhs
set -a; source .env.local; set +a

# 全量测试（不含真库评测）
mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp

# 真库评测门禁（需真 key，20-40 分钟）
mvn test -pl my-xhs-ai-app -Peval-gate

# 打包+启动（改动 tools 先 install）
mvn -pl my-xhs-ai-tools install -DskipTests
mvn -pl my-xhs-ai-app,my-xhs-ai-mcp package -DskipTests
export MYXHS_LOG_SEARCH_FILES="my-xhs-nacos=/绝对路径/nacos.log,my-xhs-mysql=..."   # 白名单必配（否则 log.search 全拒）
java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &   # 19021
java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar &   # 19020
cd frontend && npm run dev                                        # 5173（/ai 页面）

# 真实 E2E
RID=$(curl -s -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d '{"message":"为什么订单量下降了？"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['runId'])")
curl -s http://127.0.0.1:19020/api/runs/$RID          # 轮询至终态
curl -sN http://127.0.0.1:19020/api/runs/$RID/stream  # SSE 订阅
curl -s -X POST http://127.0.0.1:19020/api/ai/query -H 'Content-Type: application/json' -d '{"message":"你好"}'  # 零成本直答

# 清理残留进程
fuser -k 19020/tcp 19021/tcp 5173/tcp
```

> ⚠️ 打包前若改了 tools 模块：先 `mvn -pl my-xhs-ai-tools install` 再打 app/mcp。
> ⚠️ 删真库记录（E2E 后清理）：`mysql -u myxhs_ai_rw -p"$MYXHS_AI_DB_PASSWORD" -e "DELETE FROM my_xhs_ai.ai_step; DELETE FROM my_xhs_ai.ai_run;"`

---

## 10. 学习轨与参考

- `business-analysis/tech/architecture-c4.md`：C4 架构图（容器/组件/时序，面试一图流）
- `business-analysis/tech/gateway-integration.md`：M8-3 gateway 方案（交付物）
- `business-analysis/tech/production-roadmap.md`：M5-M9 规划 + §14 九问证据映射
- `business-analysis/tech/design-agent-harness.md` / `deploy-ai.md` / `ci-eval-gate.md` / `red-team-report-v1.md` / `security-supply-chain.md`
- `my-xhs-ai/docs/INDEX.md`（17 phase 学习轨）、`PLAN.md`（v5 总规划）
- `business-analysis/09-reference/jd/`：18 份 JD 汇总（应用岗为主；大模型厂商岗需补原理/训练/推理）

---

## 11. 面试/作品集资产

- **九问证据化**：production-roadmap §14.3（可当场复现）；新增：语义路由（三阶分层）、DECLINE 机制（安全反噬修复）、log.search（受控进程工具）
- **亮点**：不编造=架构保证（存在性校验+DECLINE 豁免）、证据链+反证+不确定性、确定性窗口注入（同题不同窗口 41.9% vs 63.9% 实证）、全真实数据闭环、Agent 反向发现 DLQ -283 哨兵值 bug、促成 4 张事件表+T-058/T-059、**语义路由取代词表穷举**、**受控日志检索（无 shell 白名单）**
- **作品集缺口**：C4 图 ✅；剩余：AgentScope 对照、Temporal kill 实验、300+ 评测集、Langfuse trace、成本/容量报告、演示视频

---

## 12. 交接检查单（新会话开始前）

- [ ] 通读本文件（v3）
- [ ] 确认代码可用：`ls my-xhs-ai-app/src my-xhs-ai-mcp/src my-xhs-ai-tools/src frontend/src/pages/ai`
- [ ] `source .env.local`（凭据就绪；MYXHS_LLM_API_KEY=OpenCode Go，成本红线仅 flash）
- [ ] 白名单配置 `MYXHS_LOG_SEARCH_FILES`（log.search 依赖）
- [ ] 遵守操作纪律：每单元小步 + 深度 review + 评测安全网
- [ ] 改动 tools 模块记得 install
- [ ] 提交 git 前确认 `.env.local` 未跟踪（`git check-ignore .env.local`）
- [ ] 密钥/凭据绝不落库、不入 git、不进文档
