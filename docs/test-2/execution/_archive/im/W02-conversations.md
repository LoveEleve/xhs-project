# W02 — 会话列表

`GET /api/im/conversations?page=1&size=20` | JWT required

## ASCII 流转图

```
[curl] → Gateway:19000 → im:19014
  → ImController.getConversations(X-User-Id=10001, ?page=1&size=20)
  → ChatService.getConversationList(MyBatis Page)
     └ MySQL:13306 my_xhs_im.t_chat_user_relation SELECT WHERE user_id=10001
  → 返回 [ConversationVO{peerId, lastContent, unreadCount, updatedAt}]
```

## 业务逻辑

分页查询当前用户的 IM 会话列表，按最后消息时间排序。每条会话返回对方用户 ID、最后一条消息内容摘要、未读计数。

## curl

```bash
curl -s "http://localhost:19000/api/im/conversations?page=1&size=3" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, 1 conversation | ✅ |
| MySQL | t_chat_user_relation: user_id=10001, peer_id=10002, unread_count=0 | ✅ |
| Data | peerId=10002, lastContent="写路由验证" | ✅ |
| SkyWalking | traceId=7b805c37810347a592610a573ab5b82f | ✅ |
| Prometheus | GET /conversations 指标已曝光 | ✅ |
