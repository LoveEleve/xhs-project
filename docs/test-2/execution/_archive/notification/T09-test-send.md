# T09 — 发送测试通知

`POST /api/notification/test/send` | `@Profile("dev")` | JWT required

## ASCII 流转图

```
[curl] → Gateway:19000 → notification:19013
  → NotificationTestController.sendTestNotification(body:NotificationEventDTO) @Profile("dev")
  → NotificationService.processEvent()
     ├ buildNotification(event) → 构建 Notification 实体
     │   ├ pushTemplateMapper.selectByType() [MySQL:13306 my_xhs_notification.t_push_template]
     │   └ Notification.setUserId(targetUserId).setType().setContent()...
     ├ aggregator.processWithAggregate() → 聚合同类型通知
     ├ unreadCountService.incrementUnread() → Redis 未读+1
     ├ sseEmitterManager.isOnline() → SSE 在线检查
     │   └ pushNotification() → SSE stream 实时推送
     └ MySQL INSERT t_notification(user_id,type,content,sender_id,is_read=0)
```

## 业务逻辑

模拟 MQ 通知事件，直接调用 Service 层。查模板构建通知标题和内容 → 聚合处理（防刷屏）→ 未读计数+1 → SSE 实时推送给在线用户 → MySQL 持久化。

## curl

```bash
curl -s -X POST "http://localhost:19000/api/notification/test/send" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" -H "Content-Type: application/json" \
  -d '{"type":1,"senderId":10002,"senderName":"testuser2","targetUserId":10001,"targetId":2085540601761169409,"targetType":1,"content":"T09测试"}'
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, "操作成功" | ✅ |
| MySQL | t_notification INSERT: user_id=10001, type=1, is_read=0 | ✅ |
| Redis | 未读计数 incremented | ✅ |
| SSE | pushNotification 在线检查 | ✅ |
| 日志 | "[通知] 处理完成: targetUserId=10001" | ✅ |
| Prometheus | POST /test/send 指标 | ✅ |
| SkyWalking | traceId | ✅ |

## 踩坑

| 问题 | 根因 | 解决 |
|------|------|------|
| 403 HMAC 拦截 | Gateway whitelist 缺 `/api/notification/test/**` | 加白名单，重启 GW |
| 404 资源不存在 | `@Profile("dev")` 未启用 | `--spring.profiles.active=dev` |
| 500 Field 'user_id' 没有默认值 | DTO 字段是 `targetUserId`，curl 误用 `receiverId` | 修正 curl body |
