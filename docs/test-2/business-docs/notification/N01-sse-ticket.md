# N01: SSE Ticket — POST /api/notification/sse/ticket

## § 源码分析

- **Controller**: `NotificationController.java:43` → `@PostMapping("/sse/ticket")`, 参数 `X-User-Id`
- **Service**: `SseTicketService.generateTicket(userId)`
  - 生成 JWT ticket: 包含 userId, 30秒 TTL, 一次性消费
  - Redis/内存存储: `ticket → userId`
- **下游**: SseTicketService(JWT)

## § 业务逻辑

两步法第一步 → 用HTTP POST(Header可带X-User-Id)获取短期ticket → 30秒有效 → 返回{ticket, expiresIn:30}

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/notification/sse/ticket` | 200, {ticket, expiresIn:30} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 30秒TTL+一次性 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/notification/sse/ticket \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl POST /api/notification/sse/ticket + X-User-Id
  → NotificationController.getSseTicket(userId)
  → SseTicketService.generateTicket(userId) → JWT ticket(30s)
  → 返回 {ticket: "xxx", expiresIn: 30}
```
