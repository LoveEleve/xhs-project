# HANDOFF-AI v1（my-xhs-ai 交接文档）

> 日期：2026-08-13 | 用途：**新会话/新上下文无缝接续**（上下文已满）。
> 通读本文件（约 10 分钟）即可接续。所有路径相对 `/data/workspace/my-xhs`。
> 配套：`my-xhs-ai/docs/INDEX.md`（17 phase 学习轨索引）、`my-xhs-ai/business-analysis/README.md`（规划总索引）。

---

## 0. 项目一句话

> 给 my-xhs 电商平台建一个**运营/运维诊断 AI Agent**：查订单/支付/内容/系统指标，做多步归因，带证据链、可追溯、不越权、不编造。
> **现状：D1/D2/D3 核心交付完成**（三个真实指标工具经 MCP 全链路 + RAG 口径问答 + 拒答），**下一步 D4（受限诊断 Agent）**。

---

## 1. 环境与凭据（安全红线）

### 目录
| 路径 | 说明 |
|------|------|
| `/data/workspace/my-xhs` | **唯一 git 仓库**（remote: `LoveEleve/xhs-project.git`，分支 main）|
| `my-xhs-ai/` | AI 项目（含 business-analysis 规划 + docs 学习轨）|
| `my-xhs-ai-app/` `my-xhs-ai-mcp/` `my-xhs-ai-tools/` | **三个代码模块**（根 pom 已注册 modules）|
| `/data/workspace/my-xhs/.env.local` | **凭据唯一来源（gitignored，确认不入库）**——别处出现即违规 |
| `/data/tmp/opencode/*.env` | 临时凭据副本（仓库外）|

### ⚠️ git 状态（2026-08-13 实测，接续关键）
| 内容 | 状态 |
|------|------|
| `business-analysis/`（82 文件）+ `docs/`（38 文件）规划文档 | ✅ 已提交（origin/main）|
| **`my-xhs-ai-app/` `my-xhs-ai-mcp/` `my-xhs-ai-tools/` 代码模块** | ❌ **未提交/未推送**（仅本地磁盘；`git status` 显示 `??` 未跟踪）|
| 仓库其他文件（config 等）| 185 个未提交（后端 P1 修复等，与本项目无关）|

> **接续环境必须从本目录获取代码**（或由你决定是否提交 AI 模块；提交前确认 `.env.local` 仍被 gitignore）。新 clone 只会有规划文档，**没有代码**。

### 凭据（`.env.local` 变量，**绝不落库/不入 git**）
| 变量 | 用途 | 备注 |
|------|------|------|
| `TEAMO_API_KEY` | **主模型** TeamoRouter | **付费档 `deepseek-v4-flash`**（2026-08-10 充值后切换；免费档工具调用不可靠曾虚构——见 §6）|
| `MYXHS_DB_USER/PASSWORD` | 只读账号 `myxhs_ai_ro`（SELECT，写拒绝 1142 已验证）| 真实 MySQL |
| `MYXHS_ROOT_PASSWORD` | 集成测试自愈 seed 用（root）| 仅测试 |
| `MYXHS_ES_PASS` | ES elastic 认证 | |
| `ARK_PLAN_API_KEY/BASE_URL/EMBEDDING_MODEL` | **RAG embedding**：火山 Agent Plan | BASE_URL 必须含 `/api/plan/v3`；模型 `doubao-embedding-vision-large`（2048 维）|
| `ARK_API_KEY` | 旧方舟 key（chat 可用；embedding 未开通，备查）| |

### 基础设施（真实，远端）
| 服务 | 地址 | 凭据 |
|------|------|------|
| MySQL | `21.130.247.89:3306`（8.0.46，orders 分片 ds0..3×t_order_0..3=16 节点）| 只读 `myxhs_ai_ro`；root 见部署包 |
| ES | `21.130.247.89:19200`（8.19.19，elastic 认证）| `elastic`/`Xhs@2026#Elastic` |
| 模型 | TeamoRouter `api.teamorouter.com/v1` | Bearer TEAMO_API_KEY |
| Embedding | 火山 `ark.cn-beijing.volces.com/api/plan/v3` | Bearer ARK_PLAN_API_KEY |

> ⚠️ 本地无 docker/MySQL；远端可连（已验证）。**git 提交前必查 `.env.local` 未被跟踪**。

---

## 2. 代码结构与端口

### 模块
| 模块 | 端口 | 职责 |
|------|:--:|------|
| `my-xhs-ai-tools` | — | **共享真实指标工具**（纯类）：`OrderMetricsTool`/`PaymentMetricsTool`/`ContentInteractionTool` + `MetricTimeWindow` + `MetricToolAccess`(接口)+`DirectMetricToolAccess` |
| `my-xhs-ai-app` | 19020 | AI 核心：模型/路由/Agent/RAG/SSE/每请求 traceId |
| `my-xhs-ai-mcp` | 19021 | **MCP 工具服务**（Streamable HTTP `/mcp`，认证+审计）|

### my-xhs-ai-app 关键类
| 类 | 职责 |
|----|------|
| `config/TeamoRouterModelConfig` | ChatModel/StreamingChatModel bean（付费 deepseek-v4-flash）|
| `config/RouterConfig` | IntentRouter bean + 可选 LLM 兜底（默认关）|
| `config/MetricToolAccessConfig` | 工具访问开关 `myxhs.ai.tools.mode=mcp`(默认)/direct |
| `config/AgentConfig` | `MetricAssistant`（AiServices + MetricToolAccess）|
| `service/router/IntentRouter` | 规则路由：归因词→AGENT（不走 LLM）；指标词→确定性；模糊→LLM兜底(默认关)|
| `service/mcp/McpToolBridge` | **MCP 薄 client**（JDK HttpClient+Jackson；会话自愈/SSE 解析/重试一次）|
| `service/rag/RagKnowledgeService` | ES REST：索引/入库/BM25/dense(kNN)/hybrid(RRF) |
| `service/rag/MetricDictionaryIngester` | 指标字典解析入库（表头识别/B面列语义/噪音过滤）|
| `service/rag/RagAnswerService` | RAG 回答闭环（检索→模型→引用；无命中拒答）|
| `controller/*` | 见端点表 |

### 端点（app:19020）
| 端点 | 说明 |
|------|------|
| `GET /api/ai/health` | 健康 |
| `POST /api/ai/chat` `/chat/stream` | 对话/SSE 流式 |
| `POST /api/ai/agent` | Agent 工具循环（AiServices）|
| `POST /api/ai/query` | **主路由**：metric→确定性工具 / agent→调查（带 traceId，全路径 error JSON）|
| `POST /api/ai/mcp/check` | MCP 全链路验证（app→桥→mcp→真库）|
| `POST /api/ai/rag/ingest` `/search` `/search-dense` `/search-hybrid` `/answer` | RAG 入库/三路检索/回答闭环 |

### MCP（mcp:19021）
- 端点 `/mcp`（Streamable HTTP）；**认证**：`MCP_API_KEY` env（设了必须 `Authorization: Bearer`，401 已测）；**审计**：`[mcp-audit] tool/window/costMs`。
- 工具：`order.query_volume` / `payment.success_rate` / `content.interaction`（口径描述+window schema）。
- 协议要点：需 `Accept: application/json, text/event-stream` + `Mcp-Session-Id`（initialize 响应头）；异步响应 202+SSE。

---

## 3. 架构摘要

```
用户 → /api/ai/query
  → IntentRouter
      ├─ 固定指标（订单量/支付成功率/互动量）→ MetricToolAccess
      │     ├─ mcp模式(默认)：McpToolBridge → my-xhs-ai-mcp(/mcp) → 真实MySQL
      │     └─ direct模式：DirectMetricToolAccess → 直接MySQL（测试/降级）
      ├─ 归因/分析（为什么/下降…）→ MetricAssistant(AiServices) → 同上工具
      └─ RAG（口径问答）→ /api/ai/rag/answer → hybrid检索 → 模型回答+引用
横切：每请求 traceId（响应==日志，logback %X{traceId}）；只读账号；MCP 认证+审计
```

**核心原则**（写码必守）：固定查询走确定性工具（数字可重复）；归因走 Agent（证据驱动）；检索不到拒答；数字必须来自工具结果（模型编造是红线——Harness 需存在性校验）。

---

## 4. 已完成（里程碑）

### D1 最小 AI 闭环 ✅
- LangChain4j 1.0.0 × Boot 3.2.5 × JDK17（手工集成，无官方 Boot starter；Jackson 无冲突 2.16.1）
- 三个真实指标工具（口径被 H2 契约+真库集成测试钉死）：
  - `order.query_volume` 61（16 分片 UNION 扫描，排除已删/含取消退款）
  - `payment.success_rate` 0.5（成功/(成功+失败)，排除 0/3；渠道 1支付宝/2微信/99 Mock）
  - `content.interaction` 48（3赞/4藏/5评/6分享 + 曝光1单列；A3 seed 数据可清理）
- IntentRouter 混合路由 + 每请求 traceId + 全路径降级（模型不可用→error JSON）

### D2 MCP 工具层 ✅
- `my-xhs-ai-mcp`（MCP SDK **0.18.3**：`io.modelcontextprotocol.sdk:mcp-core+mcp-spring-webmvc+mcp-json-jackson2`）
- 认证（McpAuthFilter）+ 审计 + 契约/认证测试（McpContractTest 3 + McpAuthTest 3）
- **全链路**：Agent/路由默认走 MCP（MetricToolAccess mode=mcp）→ 真库
- ⚠️ 弃用 SDK client-jdk-http-client（15MB fat jar 内嵌未 relocate jackson）→ 薄协议 client 自实现

### D3 RAG ✅
- 知识库（指标字典 19 条干净）+ ES BM25/dense(kNN cosine)/hybrid(RRF k=60)
- **RAG 回答闭环**：口径问答带引用 `[来源: ...#A2]`；无命中拒答
- **Agent Plan embedding 突破**：专属 URL `/api/plan/v3` + `doubao-embedding-vision-large`

### 测试
**47/47 全绿**（tools 13 + app 28 + mcp 6）。真库集成测试（order 61/payment 0.5/content 48 自愈 seed）需 `.env.local` 注入才跑，CI 无凭据自动跳过。

---

## 5. 关键决策与 ADR

| 决策 | 内容 | 位置 |
|------|------|------|
| ADR-001 | 模型 Provider=TeamoRouter 付费 `deepseek-v4-flash`（原火山备查）| `business-analysis/decisions/ADR-001.md` |
| ADR-002/003/004 | LangChain4j 主 / MCP 官方 SDK jackson2 / Durable=V1 本地状态机 | 同上 |
| ADR-005 | **自研 UI/平台，不采用 opencode/pi 作底座**（安全模型冲突）| 同上 |
| ADR-006 | 内部单组织、中等并发，不做多租户 | 同上 |
| 工具访问 | `myxhs.ai.tools.mode=mcp`(默认)/direct | app yml |
| 路由 | 归因优先 AGENT；模糊→LLM 兜底(默认关) | `RouterConfig` |
| MCP 版本 | 0.18.3（Spring 集成模块未达 2.0 GA；2.0 后升级）| D2 文档 |
| 模型分层 | 日常查询/归因用 deepseek-v4-flash；推理模型(D4 可选) | D0 技术核验 |

---

## 6. 深度 Review 教训（重要，防重踩）

1. **免费模型工具调用不可靠会虚构**（曾"声称调了工具"却零日志/编造 12000/15000）→ 已切付费；**D4 Harness 必须做"工具结果存在性校验"**（确定性兜底，不靠模型自觉）。
2. **行为枚举勘误**：`t_user_behavior` 写读一致，仅表注释需修；P1 已把表迁 content 库（A3 就绪）。
3. **报告"⚠️ 部分"要用实测验证**（agent 500 曾被低估；seed 数据易失导致测试红）。
4. **空壳测试必须清零**（"401 测试"实际无断言 → 补真实 McpAuthTest）。
5. **坐标细节**：MCP group 是 `io.modelcontextprotocol.sdk`（非无 sdk）；Agent Plan 必须 `/api/plan/v3`。
6. **dense_vector**：similarity 是字符串；向量字段必须是数组（valueToTree 非 put）。
7. **RRF 不区分单路质量**（纯语义查询 dense 单路更准）→ rerank 是方向。
8. **ingester 表头/列语义**：B 面表格指标列在第 2 列（场景列后）——表头识别而非硬编码。

---

## 7. 已知问题/边界（如实）

| 项 | 说明 |
|----|------|
| 业务缺口 A1-A6 | 支付失败码/漏斗事件(sku浏览+加购)/曝光(关注流)/published_at 等**未补**——对应服务 Owner 待实施（`business-analysis/d0/D0-gapfill-plan.md`）|
| A3 seed 是测试数据 | 清理 SQL 见 `my-xhs-ai-app/RUN.md §5`；集成测试依赖它（自愈重 seed）|
| 只读账号主机白名单/密码轮换 | 部署项未做（dev 为 '%'）|
| MCP_API_KEY 生产必须设 | dev 未设放行（WARN）|
| 索引 `(created_at,deleted)` | 迁移脚本 `my-xhs-ai-app/src/main/resources/db/index-migration.sql` 待应用（数据量大后）|
| traceId 仅单服务 | OTel/traceparent 跨服务 = D6 |
| 审计是应用日志 | 独立不可变审计流 = D6 |
| UI | 未开始（D7/D2 壳，ADR-005）|
| ES 版本漂移 | BOM 8.12 vs 服务端 8.19（零新依赖 REST 规避）|

---

## 8. 下一步（D4 受限诊断 Agent）

### D4 目标（PLAN v6）
- 受限 Agent：plan/act/observe 有界循环 + **预算(步骤/Token/成本)** + **循环检测** + **证据链/反证/不确定性** + **HITL**
- **工具结果存在性校验**（§6.1 教训：模型声称用工具但 Harness 无记录 → 拒绝）
- **基线约束确定性化**（当前提示词软约束；建议"对比窗口"工具）
- 三个业务 + 三个排障场景端到端（A1-A3/B1-B4，数据就绪度见 D0 文档）
- LangChain4j vs AgentScope Java PoC（可选，D4 对照）

### 参考设计
- `business-analysis/tech/design-agent-harness.md`（Harness 详细设计：循环状态机/预算/循环检测/HITL/证据链/checkpoint）
- `business-analysis/tech/architecture-design.md`（DAD）
- `business-analysis/plan/de-risk-before-coding.md`（A 项已实证，B 项部分）

### 数据就绪度提示
- A1 漏斗 ❌（缺商品浏览+加购事件）｜ A2 ❌（缺失败码）｜ A3 🟡（互动✅，曝光关注流缺）
- B1/B4 观测已由部署包就绪（exporter/慢查询/死锁脚本）；B2 积压 ✅

---

## 9. 如何运行/验证（新会话速查）

```bash
cd /data/workspace/my-xhs
set -a; source .env.local; set +a    # 注入全部凭据（gitignored）

# 测试（全量 47）
mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp

# 启动 MCP 服务（19021）+ app（19020）
java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar &
java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar &

# 真实 E2E
curl -X POST :19020/api/ai/query -H 'Content-Type: application/json' -d '{"message":"2026-08-01 到 2026-08-07 的下单量"}'   # → 61
curl -X POST :19020/api/ai/query -H 'Content-Type: application/json' -d '{"message":"为什么订单量下降了？"}'                  # → agent 证据驱动
curl -X POST :19020/api/ai/rag/answer -H 'Content-Type: application/json' -d '{"query":"支付成功率口径是什么"}'                # → 带引用
# MCP 直接验证（需 Accept 头 + Mcp-Session-Id）：initialize → tools/list → tools/call
```

> ⚠️ 打包前若改了 tools 模块：`mvn -pl my-xhs-ai-tools install` 再打 app（否则用旧 jar）。
> ⚠️ 清理残留进程：`fuser -k 19020/tcp 19021/tcp`（勿用 pkill 匹配自身命令行）。

---

## 10. 学习轨与参考（并行）

- `my-xhs-ai/docs/INDEX.md`：17 phase 学习轨索引（MemoryOS/AgentScope/pi 等）
- `my-xhs-ai/docs/PLAN.md`：v5 总规划（9 面试题/作品集/技术雷达）
- `business-analysis/plan/learning-path.md` + `concepts-glossary.md`
- `business-analysis/09-reference/source-study/README.md`：源码研究计划（LangChain4j→MCP→AgentScope→LangGraph）
- `business-analysis/conventions/01-operation-discipline.md`：**操作纪律**（盘问闸/小步 review/事实自查决策等你/参考只是参考）
- JD 市场总结：`business-analysis/09-reference/jd/README.md`（18 份）

---

## 11. 交接检查单（新会话开始前）

- [ ] 通读本文件
- [ ] **确认代码可用**：`ls my-xhs-ai-app/src my-xhs-ai-mcp/src my-xhs-ai-tools/src`（AI 模块**未提交 git**，若在全新环境先确认代码已拷贝/提交）
- [ ] `source .env.local`（凭据就绪）
- [ ] 读 `business-analysis/README.md`（规划总索引）定位目标文档
- [ ] 遵守操作纪律（每单元小步 + 交用户 review）
- [ ] 改动 tools 模块记得 install
- [ ] 提交 git 前确认 `.env.local` 未跟踪（`git check-ignore .env.local`）
