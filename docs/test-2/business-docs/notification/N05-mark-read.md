# N05: 单条已读 — POST /api/notification/read/{id}

## § 源码分析

- **Controller**: `NotificationController.java:95` → `@PostMapping("/read/{id}")`, 参数 `X-User-Id` + `@PathVariable Long id`
- **Service**: `NotificationService.markAsRead(userId, id)`
  - `UPDATE t_notification SET is_read=1 WHERE id=? AND user_id=? AND is_read=0`
- **下游**: MySQL t_notification

## § 业务逻辑

单条通知标记已读 → 防跨用户(必须user_id匹配)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| RateLimit | 30次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/notification/read/123` | 200 |
| MySQL | `SELECT is_read FROM t_notification WHERE id=123` | 1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id+id双重约束 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/notification/read/$NOTIFY_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/notification/read/{id}
  → NotificationController.markAsRead(userId, id)
  → UPDATE t_notification SET is_read=1 WHERE id=? AND user_id=? AND is_read=0
```
