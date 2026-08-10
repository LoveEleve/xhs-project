# W02: 会话列表 — GET /api/im/conversations

## § 源码分析

- **Controller**: `ImController.java:69` → `@GetMapping("/conversations")`, 参数 `X-User-Id` + `page/1` + `size/20`
- **Service**: `ChatService.getConversationList(userId, page, size)`
  - `SELECT * FROM t_chat_user_relation WHERE user_id=? ORDER BY updated_at DESC LIMIT ?,?`
  - size上限50 (Math.min(size, 50))
  - 映射为 ConversationVO: peer_id, last_content, last_msg_type, unread_count, updated_at
- **下游**: MySQL my_xhs_im.t_chat_user_relation

## § 业务逻辑

按更新时间倒序查会话列表 → 每行含对方userId/最后消息/类型/未读数/更新时间 → 分页返回(最大50条/页)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| IM库存在 | `SHOW TABLES FROM my_xhs_im` 含 t_chat_user_relation | 表不存在 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/im/conversations` | 200, {records:[...], total, page} |
| MySQL | `SELECT COUNT(*) FROM my_xhs_im.t_chat_user_relation WHERE user_id=?` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | user_id索引+分页 | ✅ |

## § curl

```bash
curl -s "http://localhost:19014/api/im/conversations?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/im/conversations?page=1&size=20
  → ImController.getConversations(userId, page, size)
  → SELECT * FROM my_xhs_im.t_chat_user_relation WHERE user_id=? ORDER BY updated_at DESC LIMIT ?,?
  → map → List<ConversationVO{peerId, lastContent, unreadCount}>
  → 返回 {records, total, page}
```
