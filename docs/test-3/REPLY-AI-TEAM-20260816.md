# AI 团队需求答复（造数确认 + Gateway 集成方案评审）

> 2026-08-16 | 答复方：测试/网关团队 | 依据：G2-G7 多轮回归运行态实测 + 代码/配置核对（未改任何代码）

---

## 一、需求一：业务数据造数

### 1.1 真实流量结论

**测试环境无真实流量**。当前所有业务数据均来自回归测试（G2-G7 多轮，逐用例执行），且测试纪律要求**每轮执行后数据清零恢复基线**——`t_order`/`t_payment`=0 是清理后的基线状态，**不是没流量、也不是写库链路故障**。

### 1.2 0 行归因（探索确认）

| 表 | 实测当前行数 | 归因 |
|---|---|---|
| t_order | 0（清理后基线） | **分片表**：物理表为 `my_xhs_order_{库}.t_order_{表}`（4 库×4 表，库=uid%4、表=uid/4%4）。查询裸表名 `t_order` **永远查不到数据**；且 **ai-app 数据源 URL 默认固定 `my_xhs_order_0`（仅分片 0）**——这是"查 0 行"的最可能原因 |
| t_payment | 0（清理后基线） | 非分片（my_xhs_payment 库），清理后为空，链路正常 |
| t_cart_event | **316** | ADD 167 / UPDATE 9 / CHECK 7 / CHECK_ALL 3 / DELETE 4 / CLEAR 5（+历史）——**写链路正常实证**（该表不在清理清单内故有残留） |
| t_note_event | **71** | PUBLISH 等笔记事件落库正常（G6/G7 测试产生）。AI 团队查到 0 的时间点应是清理窗口或查询库名差异 |

**写库链路无故障的完整证据链**（每轮回归验证）：
- 下单：事务消息 → t_local_message（分片）→ 库存预扣，全链路落库
- 支付：pay/99 同步 → t_payment → 回调 → 订单状态机（0→1→5 等）
- canal→RocketMQ→ES：t_note/t_spu 变更实时同步（G6-01-14 实证）
- 购物车事件：t_cart_event 316 行实证消费落库

**权限核对**：`myxhs_ai_ro` 已具备 `my_xhs_order_0~3` + analytics/cart/content/payment/product 的 SELECT——**权限无问题**。

### 1.3 造数方案（推荐方案 B，附硬性要求）

- **分片路由**：库=uid%4、表=(uid/4)%4；表名带后缀 `t_order_{n}`/`t_order_item_{n}`/`t_local_message_{n}`/`t_order_event_{n}`/`t_order_snapshot_{n}`
- **状态枚举**：订单 0 待付款/1 已付款/2 已发货/3 已完成/4 已取消/5 已退款；支付单 0 待支付/1 已支付/2 失败/3 已退款
- **漏斗口径**：浏览（t_counter count_type=5）> 加购（t_cart_event ADD）> 下单（t_order），量级约 10:3:1
- **7 天对比**：created_at 分布近 14 天（前 7 天 vs 后 7 天各一批），订单/支付/事件时间一致
- **验收口径提醒**：只读账号查询订单必须 **UNION ALL 遍历 4 库 4 表** 或经 `t_order_no_mapping` 映射表；t_payment/t_cart_event/t_note_event 单库直查
- 我方可提供一条可直接执行的 seed SQL 模板（含分片路由 + 两周时间分布）

---

## 二、需求二：Gateway 集成方案评审

### 2.1 结论：可行，但方案有 4 处关键假设与实际不符，需先调整再实施

| # | 方案假设 | 实际现状（探索确认） | 影响与建议 |
|---|---|---|---|
| 1 | **TokenRelay 转发身份**（Spring OAuth2 组件） | ❌ gateway **无 oauth2 依赖**，鉴权为自研 JWT（GatewayAuthFilter，C-07 `set()` 覆盖注入 X-User-Id 防伪造）+ HMAC（HmacSignatureFilter） | 改用现有 **X-User-Id header 注入**方案；**AI 后端需改造**：当前 AiQueryController/RunController 从 **body 读 userId（默认 "anonymous"）**，不消费 X-User-Id——需改为读取 `X-User-Id` header（会话归属与审计依赖） |
| 2 | **X-User-Role 角色头**（L1=OPERATOR / L2=TECH） | ❌ **t_user 无 role 字段**（实测列：id/username/nickname/avatar/gender/birthday/phone/email/signature/status/deleted/…）；JWT claims 仅 sub/jti/type/iat/exp；gateway 无角色注入逻辑 | **最大改动点**。需二选一：① t_user 加 role 字段 + gateway 鉴权后注入 X-User-Role；② JWT claims 扩展 role 并下发。验收项"L1 DELETE /api/runs/{id} → 403 / L2 → 200"依赖此 |
| 3 | **ai-web 静态路由**（/ai/** → ai-web-host:80） | ⚠️ uri 是**占位**（无实际 host）；`frontend/dist` 是主前端（my-xhs-frontend），**AI 诊断台前端产物位置未确定** | 需 AI 团队提供前端构建产物与静态托管方案（含 SPA fallback 到 index.html） |
| 4 | **SSE 长连接**（/api/runs/{id}/stream，不缓存不缓冲、超时 ≥31min） | gateway 路由 `response-timeout` 按服务配置（现 2-10s），**需为 ai-api 路由配置 ≥31min**；SSE 需验证 gateway 不缓冲 | 可配置，实施时明确超时阈值并实测流式响应 |

### 2.2 现有机制可直接复用的部分（确认可行）

- **路由**：`lb://my-xhs-ai-app` + Path 谓词 + metadata（response-timeout/connect-timeout/rate-limit-qps）——与现有 16 服务路由同模式
- **认证**：`/api/ai/**`、`/api/runs/**` 默认不在 JWT 白名单 → **需登录（未登录 401，符合验收）**；`X-User-Id` 自动注入（C-07）
- **限流**：Sentinel GatewayFlowRule，从路由 `metadata.rate-limit-qps` 读取（本地兜底 + Nacos 推送覆盖）——AI 诊断接口加 metadata 即可（建议 50-100 QPS，限流响应 HTTP 429 + body 429 已有文档化格式）
- **管理鉴权**：需 L2 权限的端点（如 DELETE /api/runs/{id}）可走现有 `X-Admin-Call` 模式（或角色方案落地后按角色）

### 2.3 方案遗漏项（需补充）

1. **`/api/conversations/**` 路由**：ConversationController 存在（AI 会话接口），方案未覆盖——需确认是否经网关（若前端直连 19020 则绕过统一鉴权）
2. **HMAC 免签范围**：`/api/ai/**` 默认需 HMAC 签名——前端调用需加入 hmac-white-list（或前端实现签名）；建议 `/api/ai/**`、`/api/runs/**` 免 HMAC（登录态由 JWT 保证）
3. **MCP（19021）**：确认仅内网（app→mcp），不暴露公网；MCP_API_KEY 环境变量注入（沿用 `myxhs.admin.token`/`myxhs.internal.token` 的 tokens.env 模式）

### 2.4 实施前需 AI 团队确认（3 项）

| # | 待确认项 | 说明 |
|---|---|---|
| 1 | 角色字段方案 | t_user.role 新增字段 vs JWT claims 扩展——决定 gateway 注入逻辑与 DB 变更 |
| 2 | AI 前端产物 | 构建产物位置/静态托管方式（当前 my-xhs-frontend dist 非 AI 前端） |
| 3 | SSE 超时阈值 | ai-api 路由 response-timeout 具体值（建议 ≥31min） |

### 2.5 排期预估

- 代码改动小（gateway yml 路由+白名单+限流 metadata、user 角色字段、AI 后端 X-User-Id 读取改造），**预计 1-2 个工作日**（含联调）
- 前置依赖：AI 团队对 2.4 三项的答复

---

## 三、待办清单（我方）

- [ ] （可选）提供订单/支付/事件 seed SQL（分片 + 两周分布），供 AI 团队自建 my_xhs_ai 口径数据
- [ ] 待 AI 团队答复 2.4 三项后，实施 gateway 集成（路由/认证/限流）并实测

---

## 四、复核新增确认（2026-08-16 二次核对）

### 4.1 AI 前端归属 ✅（确认贵方 2.4-②）
- `frontend/src/pages/ai/AgentConsolePage.tsx` 存在，`/ai` 路由已在主前端 App.tsx（Route path="/ai"）——**无需独立静态站**，复用主前端托管 + SPA fallback 即可

### 4.2 ⚠️ 路由路径映射修正（重要，与方案 §2 不一致）
- **前端实际请求**：API baseURL=`/ai-api`（src/api/ai.ts），请求形如 `/ai-api/api/runs`、`/ai-api/api/ai/query`；SSE 用 `EventSource('/ai-api/api/runs/{id}/stream')`
- **vite dev 代理**：`/ai-api` → 19020 + **rewrite 去掉 /ai-api 前缀** → 后端收到 `/api/runs`、`/api/ai/**`（与 Controller 路径一致）
- **方案 §2 的路由 `Path=/api/ai/**,/api/runs/**` 与前端请求不匹配**（前端请求带 `/ai-api` 前缀，不会命中该 Path）
- **正确配置**：gateway 路由 `Path=/ai-api/**` + `RewritePath=/ai-api/(?<seg>.*), /$seg`（与 vite dev 代理语义完全一致）→ uri `lb://my-xhs-ai-app`
- 同理 `/api/conversations/**` 前端经 `/ai-api/api/conversations/*` 访问，同上一条路由覆盖（RewritePath 后命中）

### 4.3 交付物
- **seed SQL 模板**：`docs/test-3/seed-ai-diagnosis.sql`（分片路由 4 用户覆盖 4 分片库、订单 8 单覆盖状态 0-5、支付 6 行、加购 18、笔记事件 20、浏览 60——漏斗 60:18:8 ≈ 10:3:1.3、前后 7 天对比、幂等 ID 段、含自验 SQL）
