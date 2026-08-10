# N04: 未读计数 — GET /api/notification/unread-count

## § 源码分析

- **Controller**: `NotificationController.java:85` → `@GetMapping("/unread-count")`, 参数 `X-User-Id`
- **Service**: `NotificationService.getUnreadCount(userId)`
  - `SELECT type, COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0 GROUP BY type`
  - 汇总 total = 所有分组计数之和
  - 返回 `UnreadCountVO{total, likeCount, commentCount, followCount}`
- **下游**: MySQL t_notification

## § 业务逻辑

按类型分组统计未读通知数 → 汇总total → 返回结构化计数(角标展示用)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/notification/unread-count` | 200, {total, likeCount, commentCount, followCount} |
| MySQL | `SELECT COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | GROUP BY 索引 | ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/notification/unread-count \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/notification/unread-count + X-User-Id
  → NotificationController.getUnreadCount(userId)
  → SELECT type,COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0 GROUP BY type
  → 返回 UnreadCountVO{total, likeCount, commentCount, followCount}
```
