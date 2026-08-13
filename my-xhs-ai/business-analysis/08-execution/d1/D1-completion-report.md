# D1 完成报告（最小纵向切片）

> 版本：v1.0 | 日期：2026-08-10 | 状态：✅ **D1 交付完成（Gate 核对通过）**
> 定位：D1 最小 AI 闭环的真实交付记录——交付物、Gate 核对、真实验证、遗留与下一步。

---

## 1. D1 目标与完成度

| D1 交付项（PLAN v6） | 状态 | 证据 |
|---------------------|:--:|------|
| `my-xhs-ai-app` Maven/App + 健康检查 | ✅ | `/api/ai/health` UP，端口 19020 |
| 模型 Provider 抽象（TeamoRouter，key 环境变量）| ✅ | `TeamoRouterModelConfig`（key 走 env，不落库）|
| 结构化输出 / 工具调用 / 流式 | ✅ | D1 骨架探针 4 项实证 + 契约测试 |
| 意图→工具→结果→来源闭环 | ✅ | `/api/ai/query`：意图路由→确定性工具→带 definitionVersion/asOf/source |
| SSE 流式端点 | ✅ | `/api/ai/chat/stream`（token→done）|
| token/cost 记录 | ✅ | done 事件带 input/output token |
| JUnit 契约测试（无 key 跳过）| ✅ | `labs/d1-skeleton/D1ContractTest` |
| **三个真实指标工具**（接真库）| ✅ | order(61) / payment(50%) / content(48) |
| **IntentRouter 混合路由**（规则+可选 LLM 兜底）| ✅ | 归因→AGENT；规则命中→确定性；模糊→LLM(默认关) |
| 每请求 traceId 溯源 | ✅ | 响应==日志 traceId，可追溯 |

## 2. D1 Gate 逐项核对（PLAN v6 §D1）

| Gate | 核对 | 证据 |
|------|:--:|------|
| **关键数字与工具逐字段一致** | ✅ | order 61 / payment 0.5(success4/fail4) / content 48 —— 均与直接 SQL 核对一致，且被**真实库集成测试钉死**（content 测试自愈：表空自动重跑 `db/seed-a3.sql`）|
| **模型不可用时明确降级** | ✅ | metric 路径无模型依赖（确定性）；**agent 路径已修**：模型不可用（实测坏 key）→ HTTP 200 + `{"status":"error","error":"模型暂不可用..."}` + traceId（不再 500）|
| **trace 无密钥/PII** | ✅ | 日志/响应仅 traceId + 指标数字，无密钥/未脱敏 PII |

## 3. 交付物清单

### 代码（`my-xhs-ai-app/`，Spring Boot 3.2.5 + LangChain4j 1.0.0 + TeamoRouter）
- 模型：`TeamoRouterModelConfig`（Chat/Streaming bean）
- 工具：`OrderMetricsTool` / `PaymentMetricsTool` / `ContentInteractionTool` + 共享 `MetricTimeWindow`
- 路由：`IntentRouter`（规则+可选 LLM 兜底）+ `LlmIntentClassifier` + `Intent`
- 服务：`MetricAssistant`（AiServices）、`AgentConfig`、`RouterConfig`
- 控制器：`/api/ai/health|chat|chat/stream|agent|query`
- 配置：`application.yml`（含只读账号 env、router 配置）、`logback-spring.xml`（traceId）
- 迁移：`db/index-migration.sql`（待应用）
- 运行：`RUN.md`（含只读账号恢复、seed 清理）

### 测试（**35/35**）
| 类 | 数 | 覆盖 |
|----|:--:|------|
| OrderMetricsToolTest | 7 | 口径/容错/窗口/超限 |
| PaymentMetricsToolTest | 3 | 口径/空窗/超限 |
| ContentInteractionToolTest | 3 | 口径/空窗/超限 |
| MetricRealDbIntegrationTest | 3 | **真库钉死** 61/0.5/48（content 自愈重 seed）|
| IntentRouterTest | 10 | 规则/归因/LLM 兜底行为/空串 |
| AiQueryControllerTest | 4 | 时间窗解析 |
| AiQueryMetricPathTest | 5 | metric/error/traceId/agent 降级 |

### 文档
- `d0/D1-skeleton-verification.md`（§1-16 全过程）
- `d0/D1-real-e2e-review.md`（九轮深度 review 与闭环）
- `d0/metric-dictionary.md`（v0.2 口径定稿）
- `d0/D0-gapfill-plan.md` / `d0/D0-audit.md`（已复核）

## 4. 真实验证证据（可直接演示）
```
POST /api/ai/query "2026-08-01~08-07 的下单量"   → metric, 61, traceId
POST /api/ai/query "2026-08-10~08-13 支付成功率"  → metric, 0.5 + 分渠道(99/1)
POST /api/ai/query "2026-08-10~08-13 互动量"      → metric, 48, daily 30→12→4→2
POST /api/ai/query "为什么互动下降了？"             → agent, 证据驱动归因+诚实覆盖说明
POST /api/ai/query "看看订单总额"（开LLM兜底）       → agent, 诚实"仅下单量无GMV"
```

## 5. 安全/可靠性/可观测状态
- **安全**：只读账号 `myxhs_ai_ro`（SELECT，写拒绝 1142）+ 密钥 env 不落库 + 主机白名单(部署加固项) + 授权边界已验证
- **容错**：窗口≤31天、单分片失败→partial、单表查询→error JSON、metric 路径 500→error（HTTP 200）
- **可观测**：每请求 traceId（响应==日志），logback %X{traceId}
- **口径**：三个指标口径被 H2 契约 + 真实库集成测试双重钉死

## 6. 遗留 / 下一步（如实）
| 项 | 归属 |
|----|------|
| LLM 兜底可靠性评测（路由层评测集、误路由率）| D4 |
| 基线约束：确定性"对比窗口"工具（当前提示词软约束）| D4 |
| 索引 `(created_at,deleted)` 应用（数据量增长后）| 基建 |
| 只读账号主机白名单收敛 + 密码轮换 | 部署 |
| 关注流曝光(A6) + published_at(A2) 数据补齐 → A3 完整归因 | 业务/后端 |
| OTel/Langfuse 导出 + traceparent 跨服务 | D6 |
| Run Store / runId（DAD 设计已备）| D5 |
| **dev 库易重置（A3 seed 需自愈）** | ✅ 已处理：seed 脚本 `db/seed-a3.sql` + 集成测试自愈 |

## 7. 面试可讲（一句话+证据）
> "D1 用 LangChain4j 1.0 + 只读账号接真实 MySQL，落地 3 个口径被测试钉死的指标工具、混合意图路由与每请求 traceId 溯源；订单量 61/支付成功率 50%/互动 48 均与直接 SQL 核对一致，34 测试全绿，'为什么互动下降'能证据驱动归因并诚实说明数据覆盖。"

## 8. D1 结论
- **D1 里程碑达成**：最小 AI 闭环真实运行（模型→意图→真实工具→带来源回答→可溯源），非 mock。
- **工程质量**：安全（只读/env）+ 容错 + 可观测 + 口径钉死 + 34 测试，达 D2 前基线。
- **下一步**：进入 **D2（可信工具层与 MCP）**——工具规范化/MCP 化、只读账号主机收敛、agent 降级文案。
