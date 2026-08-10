# N06: 按类型全部已读 — POST /api/notification/read-by-type/{type}

## § 源码分析

- **Controller**: `NotificationController.java:106` → `@PostMapping("/read-by-type/{type}")`, 参数 `X-User-Id` + `@PathVariable Integer type`
- **Service**: `NotificationService.markAllReadByType(userId, type)`
  - `UPDATE t_notification SET is_read=1 WHERE user_id=? AND type=? AND is_read=0`

## § 业务逻辑

按类型批量标记已读(点赞=1/评论=2/关注=3)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| RateLimit | 10次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT COUNT(*) FROM t_notification WHERE user_id=? AND type=1 AND is_read=0` | 0(已读) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id过滤 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/notification/read-by-type/1 \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/notification/read-by-type/{type}
  → NotificationController.markAllReadByType(userId, type)
  → UPDATE t_notification SET is_read=1 WHERE user_id=? AND type=? AND is_read=0
```
