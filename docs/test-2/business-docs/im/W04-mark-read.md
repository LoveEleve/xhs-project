# W04: 标记已读 — POST /api/im/read/{peerId}

## § 源码分析

- **Controller**: `ImController.java:89` → `@PostMapping("/read/{peerId}")`, 参数 `X-User-Id` + `@PathVariable peerId`
- **Service**: `ChatService.markAllRead(userId, peerId)`
  - `UPDATE my_xhs_im.t_chat_user_relation SET unread_count=0 WHERE user_id=? AND peer_id=?`
- **计数值**: `unread_count > 0` → 0
- **下游**: MySQL my_xhs_im.t_chat_user_relation

## § 业务逻辑

进入与peerId的会话 → 标记该会话已读(unread_count=0) → 前端刷新角标 → 对方总未读数更新(需另调W05)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| peerId有效 | `@PathVariable` 不为空 | 400 |
| IM库存在 | `SHOW TABLES FROM my_xhs_im` 含 t_chat_user_relation | 表不存在 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/im/read/{peerId}` | 200, ok |
| MySQL | `SELECT unread_count FROM my_xhs_im.t_chat_user_relation WHERE user_id=? AND peer_id=?` | 0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 幂等 | 重复POST已读 → 不报错 | ✅ |
| 性能 | 单行UPDATE索引命中 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19014/api/im/read/1001 \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/im/read/{peerId}
  → ImController.markRead(userId, peerId)
  → UPDATE my_xhs_im.t_chat_user_relation SET unread_count=0 WHERE user_id=? AND peer_id=?
  → 返回 ok
```
