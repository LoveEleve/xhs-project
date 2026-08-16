# AI 团队答复（对 20260816 需求答复的确认与澄清）

> 2026-08-16 | 答复方：my-xhs-ai 团队 | 依据：代码/配置核对 + 实测数据

---

## 一、造数部分

### 1.1 澄清：分片查询我们已覆盖（"仅分片 0"的推测与我们的实现不符）

- `OrderMetricsTool`：`shardsCsv` 为空时**自动展开默认 16 节点**（`my_xhs_order_0~3.t_order_0~3`，同实例跨库全限定名查询），实测证据：eval-gate 报告 `source="order_shard_scan(16 nodes)"`，查询结果为 9/46 单（非 0）。
- 数据源 URL 虽默认带 `my_xhs_order_0`，但工具层用"库.表"全限定名跨库扫描，**不依赖默认库名**。
- 权限已由贵方确认（my_xhs_ai_ro 覆盖 4 库 SELECT）✓。

### 1.2 澄清：t_note_event 0 行 = 清理窗口差异

- 我们查询固定 `my_xhs_content.t_note_event`（库名正确）；贵方实测 71 行（G6/G7 产生）——我们查 0 的时间点在清理后窗口，**非查询错误、非链路故障** ✓。

### 1.3 造数：接受方案 B，等待贵方 seed SQL 模板

- 请提供：分片路由（库=uid%4、表=uid/4%4）+ 两周时间分布（前 7 天 vs 后 7 天有对比差异）+ 状态枚举（订单 0-5/支付 0-3）的**可直接执行 seed SQL**。
- 我们按模板执行后自验：`t_order_*` 各分片行数 > 0、最近 7 天 vs 前 7 天可对比、漏斗量级约 10:3:1。
- 验收口径（UNION ALL 4 库 4 表）我们已在 `OrderMetricsTool` 实现，无需调整。

---

## 二、Gateway 方案（答复 2.4 三项待确认 + 2.3 补充项）

### 2.4-① 角色字段方案：**采用 t_user.role 新增字段**

- 理由：JWT claims 扩展需重新签发存量 token（影响面大）；`t_user.role` + gateway 鉴权后注入 `X-User-Role` 更可控、可灰度。
- 期望：贵方加字段（建议默认 `OPERATOR`）+ gateway 注入逻辑；L1/L2 枚举与方案一致（OPERATOR/TECH）。

### 2.4-② AI 前端产物：**AI 诊断台是主前端的一部分**

- `AgentConsolePage.tsx` 位于 `frontend/src/pages/ai/`（my-xhs-frontend 内）——产物即主前端 `frontend/dist`（含 `/ai` 路由）。
- 托管方案：与主前端同托管 + SPA fallback 到 index.html；无独立静态站。

### 2.4-③ SSE 超时阈值：**31 分钟**（我们同步调整）

- 我方 `SSE_TIMEOUT_MS` 现为 30min → **调整为 31min**（对齐贵方路由 `response-timeout ≥31min`）；请贵方按 ≥31min 配置并实测流式响应不缓冲。

### 2.3 补充项确认

| # | 项 | 我方结论 |
|---|---|---|
| 1 | `/api/conversations/**` 路由 | **经网关**（统一鉴权；开发期前端直连 19020，生产必须经网关）——请补进方案 |
| 2 | HMAC 免签范围 | **确认免签** `/api/ai/**`、`/api/runs/**`、`/api/conversations/**`（登录态由 JWT 保证）|
| 3 | MCP（19021）| 确认**仅内网**（app→mcp），不暴露公网；`MCP_API_KEY` 按贵方 tokens.env 模式注入 ✓ |

### 我方将执行的配套改造（答复后 1 天内）

1. **X-User-Id header 读取**：`AiQueryController` / `RunController` 的 userId 从 body 改为 **header 优先（X-User-Id）、body 兜底**（会话归属与审计依赖；开发/直连兼容）
2. **SSE 超时 30→31min**
3. 改造附带单测回归（userId 注入/超时配置）

---

## 三、我方待办确认

- [ ] 等贵方 seed SQL 模板 → 执行造数 → 自验（分片行数/窗口对比/漏斗量级）
- [ ] 等贵方 t_user.role 字段 + gateway 注入落地
- [ ] 我方 X-User-Id 改造 + SSE 超时调整（答复后 1 天内完成）
- [ ] 贵方实施 gateway 集成（1-2 个工作日预估）→ 联调
