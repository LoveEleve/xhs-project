# N03: 通知列表 — GET /api/notification/list

## § 源码分析

- **Controller**: `NotificationController.java:70` → `@GetMapping("/list")`, 参数 `X-User-Id` + optional `type` + `page/1` + `size/20`
- **Service**: `NotificationService.getNotificationList(userId, type, page, size)`
  - `SELECT * FROM t_notification WHERE user_id=?` + (type!=null `AND type=?`)
  - ORDER BY create_time DESC → MyBatis-Plus Page
  - size上限50
- **下游**: MySQL t_notification

## § 业务逻辑

分页查询通知列表 → 可选类型筛选(1点赞/2评论/3关注) → 按时间倒序 → 每页最大50

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/notification/list` | 200, Page<NotificationVO> |
| MySQL | `SELECT COUNT(*) FROM t_notification WHERE user_id=?` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 分页+索引 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/notification/list?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -20
```

## § ASCII流转图

```
GET /api/notification/list?type=1&page=1&size=20
  → NotificationController.getNotificationList(userId, type, page, size)
  → SELECT * FROM t_notification WHERE user_id=? AND type=? ORDER BY create_time DESC LIMIT ?,?
  → 返回 Page<NotificationVO>
```
