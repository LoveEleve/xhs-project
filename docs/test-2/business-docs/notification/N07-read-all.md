# N07: 全部已读 — POST /api/notification/read-all

## § 源码分析

- **Controller**: `NotificationController.java:117` → `@PostMapping("/read-all")`, 参数 `X-User-Id`
- **Service**: `NotificationService.markAllAsRead(userId)`
  - `UPDATE t_notification SET is_read=1 WHERE user_id=? AND is_read=0`
  - 批量操作: 所有类型所有未读 → 一次性标记
- **下游**: MySQL t_notification

## § 业务逻辑

一键清除所有未读通知角标 → 批量UPDATE所有is_read=0记录 → 返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| RateLimit | 5次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/notification/read-all` | 200 |
| MySQL | `SELECT COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0` | 0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id过滤 | ✅ |
| 性能 | 批量UPDATE单条SQL | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/notification/read-all \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/notification/read-all + X-User-Id
  → NotificationController.markAllAsRead(userId)
  → UPDATE t_notification SET is_read=1 WHERE user_id=? AND is_read=0
  → 返回 ok
```
