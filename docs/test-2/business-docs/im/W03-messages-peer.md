# W03: 聊天记录 — GET /api/im/messages/{peerId}

## § 源码分析

- **Controller**: `ImController.java:98` → `@GetMapping("/messages/{peerId}")`, 参数 `X-User-Id` + `@PathVariable Long peerId` + `page/1` + `size/50`
- **Service**: `ChatService.getMessageHistory(userId, peerId, page, size)`
  - `SELECT * FROM t_chat_message WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?) ORDER BY created_at DESC LIMIT ?,?`
  - 双向查询 → 所有 peerId 和 userId 之间的消息
  - size上限100 (Math.min(size, 100))
  - 映射为 ImMessageVO: id, sender_id, receiver_id, content, msg_type, created_at
- **下游**: MySQL my_xhs_im.t_chat_message

## § 业务逻辑

双向查聊天记录(我和对方的全部消息)→按时间倒序→分页(最大100/页)→返回ImMessageVO数组

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| peerId有效 | 任意userId | 返回空列表(无消息) |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/im/messages/{peerId}` | 200, {records:[...], total, page} |
| MySQL | `SELECT COUNT(*) FROM my_xhs_im.t_chat_message WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 复合索引(sender_id,receiver_id) | ✅ |

## § curl

```bash
PEER_ID=2085982901507301378
curl -s "http://localhost:19014/api/im/messages/$PEER_ID?page=1&size=20" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -20
```

## § ASCII流转图

```
GET /api/im/messages/{peerId}?page=1&size=50
  → ImController.getMessages(userId, peerId, page, size)
  → SELECT * FROM my_xhs_im.t_chat_message
      WHERE (sender_id=userId AND receiver_id=peerId) OR (sender_id=peerId AND receiver_id=userId)
      ORDER BY created_at DESC LIMIT ?,?
  → map → List<ImMessageVO>
  → 返回 {records, total, page}
```
