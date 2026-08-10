# my-xhs-notification 通知服务

> 9个HTTP端点 | NotificationController | SSE实时推送 | 聚合通知

---

## 架构概览

```
HTTP POST /api/notification/sse/ticket → 获取SSE Ticket(30秒有效)
GET  /api/notification/sse?ticket=xxx → SSE长连接(text/event-stream, 永不超时, 心跳保活)
HTTP REST CRUD → /api/notification/list /unread-count /read /read-all

后台: 其他服务(MQ/Feign) → NotificationService → 写入MySQL → SseEmitterManager 推送到在线客户端
```

## 端点清单

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| N01 | POST | `/api/notification/sse/ticket` | 获取SSE连接Ticket |
| N02 | GET | `/api/notification/sse` | SSE长连接(两步法) |
| N03 | GET | `/api/notification/list` | 通知列表(分页+类型筛选) |
| N04 | GET | `/api/notification/unread-count` | 未读计数(总+分类) |
| N05 | POST | `/api/notification/read/{id}` | 单条已读 |
| N06 | POST | `/api/notification/read-by-type/{type}` | 按类型全部已读 |
| N07 | POST | `/api/notification/read-all` | 全部已读 |
| N08 | GET | `/api/notification/sse/online-count` | SSE在线人数(X-Admin-Call) |
| N09 | POST | `/api/notification/test/send` | 测试发送(@Profile("dev") only) |

## SSE 两步法

```
Step 1: POST /api/notification/sse/ticket + Header X-User-Id
  → HTTP POST 可带 Token → SseTicketService.generateTicket(userId) → 返回 JWT ticket(30秒)

Step 2: GET /api/notification/sse?ticket={ticket}
  → SseTicketService.validateAndConsume(ticket) → 验证+一次性消费
  → SseEmitterManager.createConnection(userId) → 返回 SseEmitter(text/event-stream)
```

## MySQL

| 表 | 说明 |
|------|------|
| t_notification | 通知记录(user_id/type/content/is_read/notify_date生成列) |

> ⚠️ 聚合通知: `notify_date` 生成列按日期聚合同类通知，避免重复推送。

## ⚠️ 启动条件

`NotificationTestController` 需要 `@Profile("dev")` 才能加载。生产环境中该 Bean 不存在。
