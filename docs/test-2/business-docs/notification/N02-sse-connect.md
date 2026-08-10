# N02: SSE连接 — GET /api/notification/sse

## § 源码分析

- **Controller**: `NotificationController.java:56` → `@GetMapping(value="/sse", produces=text/event-stream)`, 参数 `@RequestParam String ticket`
- **Service**: `SseTicketService.validateAndConsume(ticket)` → userId
  - 验证 JWT ticket有效性 → 一次性消费(防重放)
  - userId==null → BizException("Ticket无效或已过期")
- **SseEmitterManager**: `createConnection(userId)` → SseEmitter(timeout=0,永不超时)
  - 注册到 ConcurrentHashMap<userId, SseEmitter>
  - 设置 onCompletion/onTimeout/onError → remove(userId)
- **下游**: SseEmitter(text/event-stream)

## § 业务逻辑

两步法第二步 → 验证ticket(一次性消费) → 获取userId → 创建SseEmitter连接(0超时+15s心跳) → 返回text/event-stream → 浏览器EventSource自动处理

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| ticket有效 | SseTicketService.validate | 401 "Ticket无效或已过期" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/notification/sse?ticket=xxx"` | text/event-stream |
| 心跳 | 浏览器收到 event:heartbeat data:ping | 每15s |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | ticket一次性消费 | ✅ |

## § curl

```bash
TICKET=$(curl -s -X POST http://localhost:19000/api/notification/sse/ticket \
  -H "Authorization: Bearer $(cat /tmp/test_token.txt)" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['ticket'])")
curl -N "http://localhost:19000/api/notification/sse?ticket=$TICKET"
```

## § ASCII流转图

```
GET /api/notification/sse?ticket={ticket}
  → NotificationController.sseConnect(ticket)
  → SseTicketService.validateAndConsume(ticket) → userId
    → null → 401
    → userId → SseEmitterManager.createConnection(userId)
      → new SseEmitter(0L) → Map<userId, emitter>
      → timeout 永不超时, 心跳 15s
      → return emitter(text/event-stream)
```
