# N09: 测试推送 — POST /api/notification/test/send

## § 源码分析

- **Controller**: `NotificationTestController.java:31` → `@RequestMapping("/api/notification/test")` → `@PostMapping("/send")`
- **Profile**: `@Profile("dev")` — 仅在开发环境加载，生产环境 Bean 不存在
- **Service**: `NotificationService.sendTestNotification(...)` — 模拟推送一条通知到指定用户
  - 不走聚合逻辑 → 直接 INSERT t_notification
  - 通过 SseEmitterManager 推送到在线客户端
- **下游**: MySQL t_notification + SseEmitterManager

## § 业务逻辑

开发/测试环境手动触发通知推送 → 绕过MQ/Fegin直接写DB+推送SSE → 用于验证通知管道和SSE连接

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| @Profile("dev") | `spring.profiles.active=dev` | 404（Bean不存在） |
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP (dev) | `curl -X POST /api/notification/test/send` | 200 |
| HTTP (prod) | `curl -X POST /api/notification/test/send` | 404 |
| MySQL | `SELECT * FROM t_notification WHERE content LIKE '%test%'` | 1行 |
| SSE | 在线客户端收到 event:notification | 弹出通知 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | @Profile("dev") 生产不可达 | ✅ |
| 调试 | 验证通知管道完整性 | ✅ |

## § curl

```bash
# 仅在 dev 环境有效
curl -s -X POST http://localhost:19000/api/notification/test/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userId":123,"content":"这是一条测试通知","type":1}'
```

## § ASCII流转图

```
POST /api/notification/test/send (仅 @Profile("dev"))
  → NotificationTestController.sendTest(userId, content, type)
  → 不走聚合 → INSERT t_notification
  → SseEmitterManager.sendToUser(userId, data)
    → 在线: 实时推送 SSE event:notification
    → 不在线: 静默跳过(列表可查)
  → 返回 ok
```
