# 11 · 鉴权与工具白名单（v1.0，2026-09-18 补齐）

> 状态：M1.6 前置欠账补录。实现证据：`AiRoleResolver`、各 Controller 的 `isAdmin(...)` 调用、`McpController`、xhs 题库 ai/15。

## 1. 身份来源

| 来源 | 载体 | 解析 |
|---|---|---|
| 平台 JWT | `Authorization: Bearer <token>` | userId + role claim（消费平台，不重建账号体系） |
| 管理令牌 | `X-Admin-Call` | 与会话令牌等值比对（常量时间）→ ADMIN |
| 内部令牌 | `X-Internal-Call` | 同上 → ADMIN（服务间/运维） |

## 2. 角色模型

| 角色 | 获得方式 | 能力 |
|---|---|---|
| VIEWER（默认） | 任意有效 JWT，无 role claim | 对话/Agent、查自己的会话与审批、知识检索 |
| OPERATOR | JWT `role=OPERATOR` | VIEWER + 常规操作类能力（仍不能管理全局配置） |
| ADMIN | 管理/内部令牌 或 JWT `role=ADMIN` | 管理端点：知识重索引、评测触发、消费位点诊断、MCP 直连 |

## 3. 端点分级（fail-closed）

| 端点 | 级别 |
|---|---|
| `/actuator/health`、回环 `prometheus` | 公开（仅本机抓取） |
| `/api/ai/chat`、`/api/ai/agent/**` | 有效 JWT（401 否则） |
| `/api/ai/sessions/**` | 本人（会话归属校验） |
| `/api/ai/approvals/**`（列表/回复/执行） | 本人审批（他人 404/403）；执行类另需 internal |
| `/api/ai/knowledge/reindex`、`/api/ai/knowledge/eval*` | ADMIN（403 否则） |
| `/api/ai/approvals/diagnostics/**`、`/api/ai/mcp/**` | ADMIN |

## 4. 工具白名单与危险动作

- 16 个自研工具静态注册（DLQ/日志/指标/知识/代码五类）；ES DSL 与 PromQL 服务端拼装 + 白名单校验；
- **危险工具**（`dlq_redeliver`）一律走 HITL：提案 `pending_approval` → 人工审批 → 执行 → 位点核验；
- MCP（运维直连）：server 白名单 + server-qualified 工具名 + 缓存 TTL 10min，未知工具快速失败；
- 参数：clamp（时间窗/条数/长度）+ 服务名/索引正则白名单。

## 5. 失败语义与审计

- 未认证 401 / 越权 403 / 参数非法 400（`GlobalExceptionHandler` 统一，2026-09-18 补齐缺参 400）；
- 所有管理动作写审计（只追加 + 哈希链）；审批/重投留痕含 actor/action/target/结果；
- 安全回归：红队 8 项（未授权/注入/密钥诱导/越权审批/危险工具/洪水/方法混淆/直调）全拦截。

## 6. 边界

- 无细粒度资源级鉴权（如"只能重投 A 服务死信"）；依赖平台 claim 合规（平台若滥发 OPERATOR 等同放权）；
- 多租户未做（tenant 维度隔离/配额为设计项）。
