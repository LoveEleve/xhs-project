# D1 骨架实证报告（LangChain4j 1.0 + 火山方舟）

> 版本：v0.1 | 日期：2026-08-10 | 状态：✅ 实证通过
> 工程：`../../labs/d1-skeleton/`（Maven，JDK17，langchain4j 1.0.0）
> 目的：回答去风险清单 A1/A2/A3——框架兼容、Agent 能力边界、模型能力。**用真实结果回答，不猜。**
> ⚠️ **Provider 切换**：2026-08-10 由 火山方舟 切换为 **TeamoRouter `deepseek-v4-flash-free`（免费档）**，4 项探针在两者上均全过；下文结果为 teamo 复核一致。

---

## 1. 构建了什么
| 文件 | 作用 |
|------|------|
| `pom.xml` | langchain4j-bom 1.0.0 + langchain4j-open-ai + JUnit5 |
| `VolcanoConfig.java` | 火山 OpenAI 兼容端点接入（key 仅读 `ARK_API_KEY` 环境变量）|
| `OrderTools.java` | 2 个 `@Tool` mock 工具（order.query_volume / payment.success_rate）|
| `ProbeRunner.java` | 4 项能力探针（chat/结构化/工具循环/流式）|

## 2. 实证结果（真实调用 deepseek-v4-flash）

| # | 能力 | 结果 | 证据（真实输出摘录）|
|:--:|------|:--:|------|
| ① | Chat | ✅ | "我是DeepSeek…" |
| ② | 结构化输出（AiServices→POJO）| ✅ | `OrderVolumeReport[metric=order.query_volume, volume=12850, unit=单, source=mock/order_daily_summary]` |
| ③ | 工具调用多步循环 | ✅ | "总订单量为 **12,850 单**，日均约 1,836 单"（工具被调用并基于结果作答）|
| ④ | 流式（SSE 前置）| ✅ | 增量 token 正常 |

## 3. 对去风险清单的结论

### A1 兼容性 —— ⚠️ 部分验证
- ✅ LangChain4j 1.0.0 + JDK17 **独立工程可编译可运行**。
- ⏳ **Spring Boot 3.2.5 集成未验证**（本骨架为纯 Java，隔离变量）——下一步 D1 用 Boot 3.2.5 建 `my-xhs-ai-app` 时验证 starter 兼容 + 依赖树。
- 注意：**LangChain4j 1.0 API 大改**（`ChatModel`/`ChatRequest`/`StreamingChatResponseHandler`，`@Tool` 移入 core、`AiServices` 在聚合模块）——参考资料要以 1.0 为准，别用旧教程。

### A2 Agent 能力边界 —— ✅ 关键结论
- **AiServices 能自动跑 model↔tool 多步循环**（③ 实证：模型调工具→执行→基于结果作答）。
- **但仍需自研 Harness 外层**：AiServices 提供的是基础循环，**预算/循环检测/HITL/证据链/部分结果仍要我们自己写**（对应 DAD 的 Harness 设计）→ **印证"框架 Agent 兜底 + 自研 Harness 控制边界"的路线**，不是二选一。
- 追加发现：模型会对工具结果做**轻量推算**（12850/7≈1836"日均"）——可接受，但确定性数字必须来自工具（红线不变）。

### A3 模型能力 —— ✅ 全部支持
- 火山 `deepseek-v4-flash`（ep-20260810195949-k9lkt）**支持**：chat / 结构化输出(JSON→POJO) / **工具调用(function calling)** / 流式。D1 无模型卡点。

## 4. 安全
- ARK key **未硬编码、未入库**：代码只读 `ARK_API_KEY`；本地运行从 `/data/tmp/opencode/ark.env`（仓库外，权限 600）加载。✅

## 5. D1 下一步（基于本实证）
1. **A1 补齐**：用 Spring Boot 3.2.5 建 `my-xhs-ai-app`，验证 LangChain4j 集成 + `mvn dependency:tree`（Jackson/MCP 冲突排查）。
2. **真实指标工具**：把 `order.query_volume` 从 mock 换成接 my-xhs 真实数据（D0 数据缺口先决）。
3. **SSE 接线**：流式已证可行，接入 Spring WebFlux/SSE 端点。
4. **Runner→test**：把 4 项探针固化为 JUnit 契约测试（无 key 时跳过），进评测集。

## 6. 经验教训（写码不踩）
- LangChain4j 1.0 结构化输出：**prompt 必须显式"从文本提取、不得用默认值"**，否则模型回默认值（第一版探针 volume=0）。
- 断言要处理格式：模型输出 `12,850`（千分位）≠ `12850`。

---

## 7. A1 补齐：Spring Boot 3.2.5 集成验证（2026-08-10）

> 工程：`../../my-xhs-ai-app/`（继承项目根 pom，用项目管理的 Boot 3.2.5 / Jackson 2.16.1）。

### 结论
| 项 | 结果 |
|----|:--:|
| LangChain4j 1.0.0 × 项目 Spring Boot 3.2.5 | ✅ 编译/打包/运行通过 |
| 解析版本 | spring-boot 3.2.5（项目管理生效）|
| Jackson 冲突 | ✅ **LangChain4j 1.0.0 不依赖 Jackson**（依赖树 jackson 为零）→ 无 2/3 冲突风险 |
| 端到端 | ✅ `GET /api/ai/health` UP + `POST /api/ai/chat` 真实模型回复 |
| 端口 | 19020（按 ADR/端口表）|

### 关键发现：LangChain4j 1.0.0 无官方 Spring Boot starter
- 官方 Spring 集成在独立仓库 `langchain4j/langchain4j-spring`（377⭐，版本线 1.19.x，含 boot3/boot4 两个 starter 模块），**与 1.0.0 核心版本不同轨**。
- **决策**：**手工集成**（`@Bean ChatModel`，见 `my-xhs-ai-app/.../config/VolcanoModelConfig.java`），不引 starter——符合"自研 Harness 控制边界"路线，也避免版本错配。
- MCP SDK 的 jackson2 模块冲突风险仍留待 D2 建 `my-xhs-ai-mcp` 时用 `dependency:tree` 验（PLAN §4.3）。

### D1 下一步
1. 真实指标工具：`order.query_volume` 从 mock 换接 my-xhs 数据（先决：D0 数据缺口/指标字典）。
2. SSE 接线：流式已证，接 Spring WebFlux/SSE 到 `/api/runs/{id}/events`。
3. IntentRouter 最小版（规则判定）+ 契约测试固化探针。

---

## 8. D1 纵向切片补完：SSE + token/cost + 契约测试（2026-08-10）

> 在 `my-xhs-ai-app` 补齐 D1 剩余交付项，全部实测通过。

### 8.1 SSE 流式端点 ✅
- **新增** `POST /api/ai/chat/stream`（SseEmitter + `StreamingChatModel`）。
- 事件流：`event:token`(delta 增量) → `event:done`(token 统计) → 结束。
- 实测输出节选：
  ```
  event:token  data:{"delta":"my-xhs"}
  event:token  data:{"delta":" 是一个融合内容分享"}
  ...
  event:done   data:{"outputTokens":110,"inputTokens":13}
  ```
- 意义：`/api/runs/{id}/events`（Run 级 SSE）的前身，与 UI 差分渲染对接。

### 8.2 token/cost 记录 ✅
- `ChatResponse.tokenUsage()` 已接入 done 事件（input/output token）→ 为 CostTracker（D6）铺路。
- 免费档实测：单次流式 ~13 in / 110 out token。

### 8.3 契约测试固化 ✅
- `labs/d1-skeleton/.../D1ContractTest.java`：chat / 结构化 / 工具循环 / 流式 4 用例。
- **无 TEAMO_API_KEY 自动跳过**（`@EnabledIfEnvironmentVariable`），CI 无 key 不红。
- 实测：`Tests run: 4, Failures: 0, Errors: 0`。
- 定位：进 `evals/` 的 **smoke 层**（PR 跑）。

### 8.4 D1 最小纵向切片完成度
| D1 交付项 | 状态 |
|-----------|:--:|
| my-xhs-ai Maven/App + 健康检查 | ✅ |
| 模型 Provider 抽象（TeamoRouter，key 环境变量）| ✅ |
| 结构化输出 / 工具调用 / 流式 | ✅ |
| 意图→工具→结果→来源闭环（mock 工具）| ✅ |
| SSE 流式端点 | ✅ |
| token/cost 记录 | ✅ |
| JUnit 契约测试（无 key 跳过）| ✅ |
| 最小 trace | ⏳ 下一步 |
| 真实指标工具（接 my-xhs 数据）| ⏳ 依赖 D0 缺口/指标字典 |

> **结论**：D1 可验证的纵向切片已闭环；剩余依赖 D0 数据缺口（A1-A6）与指标字典，以及 D0 剩余 5 个决策。

---

## 9. D1 真实指标工具（2026-08-10，指标字典 v0.2 定稿后）

> 把 `order.query_volume` 从 mock 升级为**真实工具**（按已拍板口径），口径用 H2 契约测试验证。

### 交付
- **`OrderMetricsTool`**（`my-xhs-ai-app/.../service/metric/`）：
  - 口径（指标字典 §3#8）：**排除已删(deleted=0)、含取消/退款（不按 status 过滤）**，按 `created_at` 时间窗，Asia/Shanghai。
  - 分片：`t_order_0~3` 固定 UNION 扫描（禁止任意 SQL）。
  - 返回确定性 JSON：`metric/definitionVersion(order.order_volume/v1)/window/zone/asOf/source`。
  - 时间窗解析 `yyyy-MM-dd~yyyy-MM-dd` → 半开区间 `[from, to+1天)`，非法窗抛错。
- **H2 契约测试** `OrderMetricsToolTest`（4 用例全过）：
  - 口径验证：8-01 正常 + 8-03 取消 + 8-05 退款计入；8-06 已删排除；7-31 窗口外排除；8-08 半开边界排除 → **value=3** ✅。
  - 空窗口=0 / 非法窗抛错 / 单日边界。
- **接入 Agent 循环**：`MetricAssistant`（AiServices）+ `AiAgentController`（`POST /api/ai/agent`）+ `AgentConfig`。
- 数据源：`MYXHS_DB_*` 环境变量接真实 MySQL（云部署）；本地无库 → 口径由 H2 测试验证。

### 结论
- ✅ **下单量口径已用测试钉死**（排除已删/含取消退款/窗口/时区），为云主机接真库和评测集提供基准。
- ⏳ 云部署联调：注入 `MYXHS_DB_*` 指向真实 MySQL 后，`/api/ai/agent` 即通。
- 下一步候选：`payment.success_rate`（A2 口径已定）、IntentRouter、OTel trace。

---

## 10. D1 真实端到端达成（2026-08-10，里程碑 M1 ✅）

> 接入**真实 MySQL**（部署包地址 21.130.247.89:3306，root/Xhs@2026#MySQL）+ TeamoRouter 模型，`/api/ai/agent` 完整闭环。

### 真实数据核对
- 拓扑：ShardingSphere `ds0..3 × t_order_0..3` = **16 个实际节点**（每库 4 表，`my-xhs-order/sharding-config.yaml:76`）。
- ⚠️ **教训**：初版"对角线"分片（db0.t0…db3.t3）只覆盖 4 节点 → 查到 1；**修正为全 16 节点 UNION** → 真实总数 **61**。
- 工具默认分片改为代码生成 16 节点（`OrderMetricsTool`）。

### 真实端到端结果
```
POST /api/ai/agent  "查一下 2026-08-01 到 2026-08-07 的下单量，用一句话回答"
→ {"reply":"2026年8月1日至8月7日期间，下单量为 **61 单**。"}
```
- 模型 → 工具调用 → 真实 MySQL(16节点) → 带数字回答。**数字=工具真实返回，非模型编造**（61 与直接 SQL 核对一致）。
- 数据源默认已指向真实地址（不再 localhost 占位）；密码默认值来自部署包（P-D1 待办，生产改 env 注入）。

### 里程碑意义
- **D1 M1「最小 AI 闭环：查询订单量并带来源回答」达成**（真实数据，非 mock）。
- 后续：`payment.success_rate`（同法实现）、IntentRouter 区分 指标/Agent、OTel trace。

---

## 11. D1 payment.success_rate 真实工具（2026-08-10）

> A2 支付成功率，同法实现 + H2 测试 + 真实 E2E。

### 实现
- **`PaymentMetricsTool`**（`my-xhs-ai-app/.../service/metric/`）：
  - 口径（指标字典 §3#7）：**成功(status=1) / (成功 + 失败(status=2))**，**分母排除 0待支付/3退款**；时间基准 `created_at`；Asia/Shanghai；窗口≤31天。
  - 表：`my_xhs_payment.t_payment`（单表不分片）；**分渠道**（pay_type，Mock 须注明）。
  - 返回：`value/rate` + `success/fail` + `channels[]` + `asOf/source/note(渠道为Mock)`。
  - 共享 `MetricTimeWindow`（与 order 工具复用窗口解析/上限）。
- **H2 契约测试** `PaymentMetricsToolTest`（3 用例）：口径（排除待支付/退款/已删/窗外 → success=3 fail=1 rate=0.75 + 分渠道 1.0/0.5）、空窗口=0、非法/超限抛错。
- **接入 Agent**：`AgentConfig` tools 加 payment 工具。
- 全量测试：**10/10**（order 7 + payment 3）。

### 真实 E2E（只读账号）
```
POST /api/ai/agent  "查一下 2026-08-10 到 2026-08-13 的支付成功率"
→ {"reply":"2026-08-10 到 2026-08-13 的支付成功率为 **50%**（成功 4 笔 / 失败 4 笔）。"}
日志: [metric] payment.success_rate window=2026-08-10~2026-08-13 rate=0.5000 success=4 fail=4 channels=2
```
- 与直接 SQL 核对一致（成功4/失败4/退款1 排除）；数字来自工具非模型编造。

### D1 现状
- ✅ 两个核心指标工具真实可用：`order.query_volume`（61）+ `payment.success_rate`（50%），口径全部由测试钉死 + 真库核对。
- 下一步候选：IntentRouter（区分 指标/Agent）、OTel trace、A3 互动指标工具。

---

## 12. D1 IntentRouter（2026-08-10）

> PLAN §2 原则落地：固定查询→确定性工具（不走 LLM），归因/分析→Agent。

### 实现
- **`Intent`** 枚举：`METRIC_ORDER_VOLUME` / `METRIC_PAYMENT_RATE` / `AGENT`。
- **`IntentRouter`**（规则判定，纯逻辑可测）：关键词匹配 + **归因优先**（为什么/为何/原因/怎么/分析/下降/降低/异常 → AGENT，优先于指标词）。
- **`POST /api/ai/query`**：metric 意图 → 工具直取（确定性 JSON）；agent 意图 → `MetricAssistant`。
- 时间窗提取：从消息取日期对；无则默认最近 7 天。

### ⚠️ 测试暴露并修复的设计 bug
- 初版只按指标关键词匹配 → **"为什么订单量下降了"被错路由到确定性路径**（违反"归因走 Agent"原则）。
- **修复**：归因/分析词优先 → AGENT。测试 `IntentRouterTest`（4 用例）钉死该行为。

### 真实路由验证（/api/ai/query）
| 请求 | 路由 | 结果 |
|------|:--:|------|
| "查一下 2026-08-01~08-07 下单量" | **metric**（不走LLM）| 61（确定性 JSON）|
| "2026-08-10~08-13 支付成功率" | **metric** | 0.5 + 分渠道 |
| "为什么订单量下降了？" | **agent** | **Agent 主动查两窗口（43 vs 61）→ 纠正前提："未下降，反升 +41.9%"**——证据驱动归因，非瞎答 |

### 测试
- 全量 **16/16**：order 7 + payment 3 + 真实库集成 2 + IntentRouter 4。
- IntentRouter 纯逻辑，CI 无需凭据/网络。

### D1 意义
- "固定查询不走 LLM、开放归因走 Agent"的**路由雏形已落地且真实验证**；为 D4 IntentRouter 完整版（含更多意图/指标词/LLM 兜底）铺路。

---

## 13. D1 可观测：每请求 traceId 溯源（2026-08-10）

> 目标：**每次运行可追溯**（traceId/runId 关联）——响应、日志、工具调用三方对得上。

### 实现
- **手动 MDC traceId**：`AiQueryController.query` 每请求生成 traceId → 入 MDC（`logback %X{traceId}`）→ 同线程工具/模型日志自动带上 → 响应返回 `traceId`。
- **logback-spring.xml**：pattern 含 `%X{traceId:-}`。
- **失败**：metric 路径 `status:error` + traceId 仍返回（可溯源定位）。

### ⚠️ 踩坑记录（写码不踩）
- **Boot 3.2.5 的 `management.tracing`（micrometer-tracing-bridge-otel）无 OTLP collector 时不产出 `Tracer` bean** → 启动失败；`SimpleTracer` 兜底在 micrometer-tracing 1.2.5 不存在 → **放弃 OTel 自动配置**，改用手动 MDC traceId（确定、可测）。
- 真实 **OTel/Langfuse 导出是 D6**（有 collector/Langfuse 时再上 micrometer-tracing + OTLP 端点）。

### 验证
```
POST /api/ai/query  "2026-08-01~08-07 下单量"
响应 traceId: b5659c40daf7456a8a6472a4b980176b
日志: INFO b5659c40daf7456a8a6472a4b980176b ... OrderMetricsTool - [metric] order.query_volume ... volume=61
✅ 响应 traceId == 日志 traceId（同一请求全链路可溯源）
```
- 全量测试 **23/23**。

### D1 意义
- "每次运行可追溯"（PLAN 成功标准/DoD）已落地最小闭环；traceId 作为未来 runId/stepId 关联的锚（D5 Run Store、D6 Langfuse）。

---

## 14. D1 content.interaction 真实工具（2026-08-10）

> A3 内容互动，第三个真实业务工具。行为链路已修复（P1，表在 content 库）。

### 实现
- **`ContentInteractionTool`**（`my-xhs-ai-app/.../service/metric/`）：
  - 口径（指标字典 A3）：每日新增互动 = `t_user_behavior` **code 枚举 3=赞/4=藏/5=评/6=分享**（deleted=0），按日分组；**曝光（type=1，推荐流）单列**（关注流曝光待 A6）。
  - 表：`my_xhs_content.t_user_behavior`（P1 修复后落 content 库）。
  - 返回：`total` + `daily[]`（按日 like/favorite/comment/share）+ `exposure` + `asOf/source/note`。
  - 窗口≤31天、try-catch→error JSON、`CAST(created_at AS DATE)`（MySQL/H2 兼容）。
- **路由**：新增 `METRIC_CONTENT_INTERACTION` 意图（互动量/点赞/收藏/评论数/分享数/曝光量关键词）。
- **H2 契约测试** `ContentInteractionToolTest`（3 用例：按日互动+曝光单列=7/1、空窗=0、非法/超限抛错）。

### 真实 E2E（只读账号）
```
① POST /api/ai/query "2026-08-01~08-07 的互动量" → metric, content.interaction, total=0, exposure=0
② POST /api/ai/query "为什么互动下降了？" → agent，主动用互动工具查 3 窗口，如实报告"0 互动/0 曝光"（未编造）
```
- ⚠️ **真实数据为空（t_user_behavior 0 行）**：P1 修复后链路通了但尚无新行为事件 → 工具返回 0 是**诚实结果**；A3 需行为事件产生（或 seed 测试数据）才有真实归因。

### 测试
- 全量 **28/28**：order 7 + payment 3 + content 3 + 集成 2 + IntentRouter 5 + AiQueryController 4 + AiQueryMetricPath 4。

### D1 现状
- **三个真实业务工具**：order.query_volume(61) / payment.success_rate(50%) / content.interaction(0，待数据)。
- 下一步候选：行为数据 seed（A3 真值）、IntentRouter 完整版、OTel/Langfuse（D6）。

---

## 15. A3 行为数据 seed + 归因演示（2026-08-10）

> 让 content.interaction 有真值可演示（此前 0 行）。

### seed（真实库，标注测试数据）
- 向 `my_xhs_content.t_user_behavior` 插入 64 行（合成 user 1001-1302 + 真实 note `2087825399137484801`），**骤降模式**：
  - 08-10：互动 **30**（赞20/藏5/评3/分享2）+ 曝光 16
  - 08-11：互动 **12** ｜ 08-12：**4** ｜ 08-13：**2**
- id 用 9e18 段避开业务 id；`ON DUPLICATE KEY` 幂等。

### 确定性查询验证
```
POST /api/ai/query "2026-08-10~08-13 互动量" → metric, total=48, exposure=16
daily: 08-10:30 → 08-11:12 → 08-12:4 → 08-13:2（骤降清晰）
```

### Agent 归因演示（"为什么互动下降了？"）
- **提示词修正**（基于实测）：初版"当前 08-01~08-07"示例被模型误当当前窗口（实际数据在 08-10+）→ 修正为"**当前窗口未指定时取最近 7 天；基线=上一同长窗口**"。
- 修正后行为：Agent 用当前 08-07~08-13 + 基线 07-31~08-06 → 看到 **48 总互动、日内骤降 30→2**，并**诚实指出基线窗口无数据、无法做环比**（不编造）。
- **基线约束从"软的提示词"升级为有效行为**；D4 仍建议确定性"对比窗口"工具兜底。

### 意义
- A3 场景从"工具能查但无数据"变为**可真实演示**：确定性查询 + 证据驱动归因 + 诚实数据覆盖说明。

---

## 16. IntentRouter 完整版：混合路由（规则 + 可选 LLM 兜底，2026-08-10）

> D4 方向前置：规则优先 + 仅模糊时 LLM 兜底，默认关闭，规则路径保持确定性。

### 设计
- **IntentRouter**：归因词（为什么/下降…）→ **直接 AGENT**（不走 LLM 兜底，防"为什么订单量下降"被误路由到指标）；规则命中 1 个指标词 → 确定性；**0 个或 >1 个 → 模糊** → LLM 兜底（开启时）否则 AGENT。
- **`LlmIntentClassifier`**（接口）+ `LlmIntentClassifierImpl`（AiServices 结构化输出 → Intent；失败保守回 AGENT）。
- **`RouterConfig`**：`myxhs.ai.router.llm-fallback.enabled=false` 默认关；开启才注册 LLM 分类器 bean。

### 测试（IntentRouter 9 用例，含 LLM 兜底行为）
- 规则命中不走 LLM（fake 分类器返回错误也不影响）✅
- 归因词不走 LLM（即使 LLM 会误路由）✅
- 规则模糊 + LLM 兜底 → 按 LLM 路由 ✅
- 规则模糊无 LLM → 保守 AGENT ✅
- 全量 **33/33**。

### 真实验证（开启 LLM 兜底）
| 请求 | 路由 | 行为 |
|------|:--:|------|
| "看看订单总额"（模糊，非固定指标）| agent | **LLM 正确判断"总额≠下单量(GMV 无此源)"→ AGENT** → Agent 诚实说明"只有下单量、无 GMV"，**未误路由到错误指标** |
| "为什么订单量下降了" | agent | 归因直接 AGENT，LLM 兜底未被调用 |

### 结论
- 混合路由**行为正确且默认安全**（关 LLM 时规则路径确定性，无模型依赖/成本）。
- "总额→AGENT 而非误路由"验证了 LLM 兜底的价值与归因保护的必要性。
- D4 完整版：更多指标词 + 更多工具 + 评测集覆盖路由层。
