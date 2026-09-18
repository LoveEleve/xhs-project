# 10 · SSE 业务事件契约（v1.0，2026-09-18 补齐）

> 状态：M1.6 前置欠账补录。实现证据：`AgentController.toSse()`（:112-190）、`ChatController.stream()`。

## 1. 范围与端点

| 端点 | 事件集 | 说明 |
|---|---|---|
| `POST /api/ai/chat/stream` | `delta` / `done` / `error` | 轻量聊天通道 |
| `POST /api/ai/agent/chat/stream` | `delta` / `tool` / `hint` / `approval_required` / `final` / `done` / `error` | Agent 工具循环通道 |

- 响应 `Content-Type: text/event-stream`；每个事件形如：
  ```
  event: <name>
  data: <payload>
  <空行>
  ```
- `payload`：文本内容为 JSON 字符串（`jsonData(text)`）；`tool`/`approval_required` 为 JSON 对象/工具原始输出（工具输出截断 800 字符）。

## 2. 事件语义与顺序

| 事件 | 触发 | data | 保证 |
|---|---|---|---|
| `delta` | REASONING 增量文本 | JSON 字符串 | 可多次、按模型产出顺序 |
| `tool` | TOOL_RESULT | `{"name": "...", "output": "..."}`（output ≤800 字符） | 每次工具结果一条 |
| `approval_required` | 工具结果为 `pending_approval` | 工具原始输出（含审批单信息） | 出现后本轮流终止于 `done`（等待人工审批） |
| `hint` | AgentScope HINT 事件 | JSON 字符串 | 可多次 |
| `final` | AGENT_RESULT 且 `isLast` | JSON 字符串 | 至多一次，代表最终答案 |
| `done` | 流正常结束 | 通常为空 | **终止事件**，最后一条 |
| `error` | 流内异常（模型/工具/超时） | 错误摘要（脱敏） | **终止事件**，替代 `done` |

顺序约束：`(delta|tool|hint|approval_required)* (final)? (done|error)`。

## 3. 客户端约定

- 断线重连：不提供服务端事件回放；重连=重新发起请求（携带 `sessionId` 续聊），历史由 `GET /api/ai/sessions/{id}/messages` 补齐；
- 审批流：收到 `approval_required` 后客户端应引导用户到审批接口（`/api/ai/approvals`），审批通过后再次发起对话获取 `final`；
- 幂等：请求可携带 `X-Request-Id`，重复提交回放首次结果（与同步接口一致）。

## 4. 边界

- 心跳：当前未发送注释型心跳帧（长连接由容器/网关超时兜底）；如需跨长代理可加 `:keepalive`；
- 事件载荷不做专有 schema 版本号（v1 冻结）；新增事件名向后兼容（客户端忽略未知事件）。
