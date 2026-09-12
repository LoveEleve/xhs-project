# RV06：业务贴合度与 JD 对齐深度 Review

> 日期：2026-09-12 ｜ 触发：M1.5（MCP 接入）完成后，用户要求深度 review 一轮
> 方法：4 路并行审查 —— ① xhs-ai docs 全量（27 份）② 老 `business-analysis` 资产 ③ 18 份 JD 原文 ④ 代码/运行态实测
> 结论：**不通过"无问题"结论**。技术存在 P0 问题、业务尚未闭环、JD 对齐有明确缺口；规划按"业务竖切优先"修订（§4），修订已执行。

---

## 1. 技术 Review（实测证据，只读审查）

### P0（M1.6 必须闭环）

| # | 问题 | 证据 | 修复方向 |
|---|------|------|---------|
| P0-1 | 19020 全端点无鉴权、监听全网卡；MCP 工具端点可被任意直调 | `ss -lnt` = `*:19020`；无 token `curl /api/ai/mcp/servers` → 200；`McpController.java:47-52` 无白名单 | 网关统一鉴权 + 内网令牌；工具白名单/风险分级；默认只读 |
| P0-2 | Prometheus MCP 工具集含危险项：`delete_series`/`clean_tombstones`/`reload`/`snapshot`/`quit` | 工具清单实测 28 个；`design/05-mcp.md` 白名单尚未实现 | `--mcp.tools` 允许清单（只读）+ ADR-9 策略引擎落地前默认关闭危险工具 |
| P0-3 | prometheus-mcp 自带 web 监听 `*:19081`，暴露 `/debug/pprof/` 与 `/metrics` | `curl :19081/debug/pprof/` → 200 | 绑 127.0.0.1 或 `--web.listen-address=127.0.0.1:19081`；仅本机 |
| P0-4 | 平台接线未收口：旧 `my-xhs-ai-app` 仍在根 pom 与网关路由（`lb://my-xhs-ai-app`，`/ai-api/**`）；xhs-ai 未注册 Nacos/网关；旧 app 定义同为 19020 | `my-xhs-gateway/application.yml:245-250`；根 pom modules；`my-xhs-ai-app/application.yml:2` | xhs-ai 注册 Nacos + `/api/ai/**` 路由；旧 AI 三模块与旧路由下线归档 |

### P1（阻断业务闭环）

| # | 问题 | 证据 |
|---|------|------|
| P1-1 | **模型不会调用 MCP 工具**：`tools=List.of()` | `ChatController.java:42,57` |
| P1-2 | 无会话/多轮：每次请求仅单条 UserMessage；`ai_session/ai_message` 建而未用 | `ChatController.java:41,56`；启动 WARN "No MyBatis mapper" |
| P1-3 | 无 HITL：`ai_approval/ai_session_grant` 无代码引用 | V1__init.sql 建表，grep 无引用 |
| P1-4 | 无审计落库：`ai_audit` 无写入代码 | 同上 |
| P1-5 | traceId 全链路缺失：logback 配 `%X{traceId}` 但无 MDC 埋点，JSON 日志 0 条 traceId | `logback-spring.xml:8`；`grep -c traceId /logs/xhs-ai.json` = 0 |
| P1-6 | `/actuator/prometheus` 404：无 micrometer-registry-prometheus，无 token/工具/耗时指标 | curl 404；jar 无 registry 依赖 |
| P1-7 | 无预算/重试/熔断/降级：grep Retry/CircuitBreaker/Bulkhead = 0；模型单实例 | `AiModelConfig.java:20-25` |
| P1-8 | MCP 子进程生命周期无保障：仅 `@PreDestroy close`，无进程组/pdeathsig/残留清理 | `McpClientManager.java:120-131` |
| P1-9 | 无独立进程监督：仅 oneshot 自愈脚本，xhs-ai 崩溃不拉起 | `boot-selfheal.sh:82-94`；无 systemd unit |
| P1-10 | 零测试：`src/test` 不存在；pom test 依赖未使用 | `ls xhs-ai/src/test` No such file；git log 仅 2 commit |
| P1-11 | 密钥爆炸半径：向 xhs-ai 注入全量 `tokens.env`（ADMIN_TOKEN/JWT_SECRET）；错误信息直接回显 | `boot-selfheal.sh:72`；`ChatController.java:49,66` |

### P2（登记不展开）

错误响应 HTTP 200 无 `@ControllerAdvice`；MCP 同步 block 无舱壁；`logic-delete` 指向不存在的 `deleted` 列；Redis 配置死代码；`npx` 运行时联网拉包（供应链）；actuator `show-details: always`；CORS 无策略；无 build-info/git tag；`@EnableScheduling` 空转；Flyway `baseline-on-migrate` 风险；SSE error 手工拼 JSON；README 状态滞后。

### 当前真实可用能力（务实的自我认知）

M1 骨架 ✅（对话/SSE/Flyway/JSON 日志→ELK/自愈）、M1.5 MCP ✅（ES/Prometheus/Grafana 三 server 列出并可调用）。**但**：无鉴权、无多轮会话、无工具编排（模型不用工具）、无审批、无审计、无平台入口 —— 当前形态≈"chat + 工具查询演示"，距"可用业务闭环"见 §2.2。

---

## 2. 业务贴合度 Review

### 2.1 已有真实锚点（应保持并强化）

- 场景库 34 条 REQ 直接引用 117 项测试结论与 21 个运行态修复（`requirements/01:76-78`）；
- 真实 traceId/类名/表/topic 锚点（`InventoryService.doPreDeduct`、`t_order_event`、`t_inventory_prededuct_idem`、canal、xxl_job_log）；
- 评测 ground truth 同源（`03-test-design.md:88`）；
- 数据源实测（ES `myxhs-logs-*`、VictoriaMetrics `count(up)=23`、RocketMQ 5.1.4、XXL-Job trigger 已验证）。

### 2.2 三个硬伤

1. **无竖切闭环**：没有任何一条真实任务走完"取数→诊断→证据→动作→核验→审计"。M1/M1.5 交付的是横向基建（骨架+MCP），业务价值不可见。
2. **验收悬空**：P0 场景 AC 仍是草案（`requirements/01:62`）；DIAG-11/12/13、KB-07、OPS-02/03/04 无历史案例/故障数据锚定。
3. **与"使用"脱节**：无 sessionId 多轮、无经网关的入口（P0-4）、无审批 API/界面，PRD 中研发/运维/新人三条使用路径全部走不通。

### 2.3 未复活的老资产（最大浪费）

老项目 7 张**真实案例卡**（`business-analysis/tech/business-cases-v1.md`）未进入 v2 计划，它们是"真正解决问题"的最佳素材与评测种子：

| # | 案例 | 真实结论亮点 |
|---|------|-------------|
| 1 | 订单下降归因 | 断点在加购→下单（2.79% vs 基线），带反证与不确定性 |
| 2 | 支付成功率下降 | **敢否定用户前提**（未观测到下降，疑似口径） |
| 3 | 内容互动下降 | 数据缺口下如实声明边界，不编造趋势 |
| 4 | MQ 积压 | 24 消费组 totalLag=0，与前提相反 |
| 5 | HTTP 5xx | 识别 `/actuator/health` 健康检查噪音 |
| 6 | 5xx 根因定位 | 定位到代码：`CouponFeignClient` 缺 `X-User-Id`、comment count CCE |
| 7 | DLQ 死信重投 E2E | 查 DLQ→`ORIGIN_MESSAGE_ID`→审批→`dlq.redeliver`→消费核验 |

另：老 D0 审计的 **A1-A6 数据缺口**（支付缺 failCode/failStage、漏斗缺加购事件、published_at/audited_at、取关流水、商品级退款、曝光数据）在 v2 没有"接受/修复"决策。

---

## 3. JD 对齐 Review（18 份原文已通读）

### 3.1 高频要求 vs xhs-ai v2

| 能力项 | JD 频率 | xhs-ai v2 现状 | 动作 |
|--------|:--:|-----------|:--:|
| Agent 工作流 + 工具调用 | 18/18 | 工具治理/审批已设计，**未实现**；模型尚不调用工具 | M2.0 落地 |
| RAG | 17/18 | M3 规划（ES 混合检索） | 讲法+按计划 |
| 真实业务落地 | 13/18 | 场景真实但**无竖切证据** | M2.0 竖切+案例复活 |
| 多智能体 | 13/18 | **v2 无落点**（旧 v1 有 AgentDispatcher） | M3+ 可选专项（先不做） |
| 记忆/Memory | 10-18/18 | 会话状态规划，长期 Memory 无设计 | M2.5 设计（四态边界） |
| Prompt/上下文工程 | 13/18 | 有预算/裁剪规划，未实现 | M2.0 随工具编排落地 |
| 可观测/可追溯 | ~11/18 | 设计完备（D03），**未实现**（无 traceId/指标） | M2.0 最小实现 |
| 评测/Eval | 7/18 | 设计完备（50+ 用例），未执行 | 每个竖切配 1 EVAL |
| MCP | 6/18 | ✅ 超配（三官方 server 实测通） | 保持 |
| HITL/Checkpoint | 2/18 高信号 | 设计完备，未实现 | M2.0 竖切含审批 |
| 高并发/稳定性 | 12/18 | 未实施（无预算/熔断/压测） | M4 压测+对照 |

### 3.2 关键判断

**JD18 红线**："仅做聊天机器人、简单知识库问答、开源 Demo 调试经验，不符合专家级岗位要求"；JD5 强调"并非传统问答/RAG 场景"。当前交付形态（chat + MCP 工具列举）恰好踩在红线上 —— 必须用"真实业务竖切闭环 + 真实数据 + 评测证据"证明不是 Demo。

**结论**：JD 命中面足够（Java+MCP+RAG+真实业务+评测+可观测），但**证据都停在设计层**；面试叙事需要"已上线/已跑通"的产物，产出 `reviews/07-jd-hit-matrix-v2.md` 并随进度回填。

---

## 4. 规划修订（已执行）

> 原则：**业务竖切优先、横向能力随需**；每个竖切必须配"验收指标 + 至少 1 条 EVAL + 可演示路径"。

| 阶段 | 交付 | 状态 |
|------|------|------|
| M1 骨架 | Spring Boot + AgentScope 接入、对话/SSE | ✅ 2026-09-12 |
| M1.5 MCP | ES/Prometheus/Grafana 官方 MCP tools+call | ✅ 2026-09-12 |
| **M1.6 安全与接线（新）** | 鉴权/工具白名单/19081 收口/Nacos+`/api/ai/**`/旧模块路由下线/systemd/端口收口 | ✅ 2026-09-12 |
| **M2.0 业务竖切①（新）** | DIAG-08+OPS-01：DLQ 积压诊断→审批→重投→核验→审计，端到端（会话+工具编排+traceId+审计+HITL+1 EVAL） | ✅ 2026-09-12（E2E；EVAL harness 在 M4，见 reports/m2.0-dlq-e2e.md） |
| M2 诊断横向 | DIAG P0 清单铺开，每场景配 1 EVAL；7 张真实案例卡入库 | 调整 |
| M3 知识 | RAG + 代码检索 + 三层知识入库 + 案例库 | 保持 |
| M4 治理与评测 | 评测集 50+、压测、MTTR 对照、红队、FMEA 18 项演练 | 保持 |

**竖切①选型理由**：DLQ 链路数据源现成（RocketMQ Admin + ES 日志 + ai_approval）、有历史 E2E 成功记录（`CR_SUCCESS`）、一次拉通 5 项 FR（工具编排/证据链/HITL/审计/幂等），业务价值直接可讲（运维每天真实动作）。

---

## 5. 文档修正清单

| # | 修正 | 位置 |
|---|------|------|
| C1 | 场景数口径 28 → 34（DIAG 15/KB 8/OPS 4/PLAT 7） | `requirements/02:82`、`resume:12,79`、README |
| C2 | ADR 口径 15 → 23 | `resume:12,79` |
| C3 | FMEA 口径 15 → 18（F1-F18） | `resume:23,79,89` |
| C4 | §8 "待出专项"列表过期（01-05 已产出）；SSE 契约 `design/10-sse-contract.md` 列为 M1.6 前置 | `02-architecture:28,148-154` |
| C5 | README 状态从"设计阶段"更新为"M1/M1.5 已运行"+ 新 review 索引 | `xhs-ai/README.md` |
| C6 | 场景库追加"首批竖切与真实案例复活清单 + D0 数据缺口处置建议" | `requirements/01` 新增 §7 |
| C7 | 本报告与 JD 矩阵 v2 入索引 | README |

---

## 6. 行动清单

**P0（先做）**
1. M1.6 安全与接线：鉴权、工具白名单+危险工具禁用、19081 绑本机、Nacos+网关路由、旧 AI 模块/旧路由下线、systemd、traceId 注入。
2. M2.0 竖切①：DIAG-08+OPS-01 端到端。

**P1（随竖切）**
3. 会话持久化（ai_session/ai_message）+ 多轮；ai_audit 写入；token/工具指标；预算/熔断最小实现。
4. 竖切①配 1 条 EVAL（历史 DLQ 案例 ground truth）+ 1 条 E2E。

**P2（按 JD 权重）**
5. 长期 Memory 设计（M2.5）；多智能体专项评估（M3+，先讲法不做）；SSE 契约文档（M1.6）。
