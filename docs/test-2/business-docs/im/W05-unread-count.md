# W05: 总未读数 — GET /api/im/unread-count

## § 源码分析

- **Controller**: `ImController.java:99` → `@GetMapping("/unread-count")`, 参数 `X-User-Id`
- **Service**: `ChatService.getTotalUnreadCount(userId)`
  - `SELECT COALESCE(SUM(unread_count), 0) FROM my_xhs_im.t_chat_user_relation WHERE user_id=?`
  - 单条SQL求和所有对方会话的未读数
- **下游**: MySQL my_xhs_im.t_chat_user_relation

## § 业务逻辑

客户端角标场景 → 对所有会话未读数求和 → 返回总未读数 → 角标/N×X → 与W04配合(标记已读后角标递减)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| IM库存在 | `SHOW TABLES FROM my_xhs_im` 含 t_chat_user_relation | 表不存在 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/im/unread-count` | 200, {"data":{"total":N}} |
| MySQL | `SELECT COALESCE(SUM(unread_count),0) FROM my_xhs_im.t_chat_user_relation WHERE user_id=?` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | user_id索引+SUM聚合 | ✅ |

## § curl

```bash
curl -s http://localhost:19014/api/im/unread-count \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/im/unread-count
  → ImController.getTotalUnreadCount(userId)
  → SELECT COALESCE(SUM(unread_count), 0) FROM my_xhs_im.t_chat_user_relation WHERE user_id=?
  → 返回 {total: N}
```
