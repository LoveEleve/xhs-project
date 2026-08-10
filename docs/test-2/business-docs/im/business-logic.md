# my-xhs-im 业务逻辑分析

## 一、消息流程

```
发送: WebSocket text message → JSON{"receiverId","content","msgType"}
  → ChatService.sendMessage(sender, receiver, content, msgType)
    → INSERT t_chat_message(senderId, receiverId, content, msgType)
    → UPSERT t_chat_user_relation(user_id=sender, peer_id=receiver, last_content, updated_at)
    → UPSERT t_chat_user_relation(user_id=receiver, peer_id=sender, unread_count+1)
    → Redis PUBLISH myxhs:im:route:{receiver} → 实时推送
```

## 二、会话列表

```
getConversations(userId, page, size):
  SELECT * FROM t_chat_user_relation WHERE user_id=? ORDER BY updated_at DESC LIMIT ?,?
  → 每行: peer_id(对方userId), last_content, last_msg_type, unread_count, updated_at
  → 前端按最后消息时间排序
```

## 三、历史消息

```
getMessageHistory(userId, peerId, page, size):
  SELECT * FROM t_chat_message WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)
  ORDER BY created_at DESC LIMIT ?,?
  → 双向查询 → 分页返回(每页最大100)
```

## 四、标记已读

```
markAllRead(userId, peerId):
  → UPDATE t_chat_user_relation SET unread_count=0 WHERE user_id=? AND peer_id=?
  → 进入会话即标记该会话已读
```

## 五、未读计数

```
getTotalUnreadCount(userId):
  SELECT SUM(unread_count) FROM t_chat_user_relation WHERE user_id=?
  → 返回 {total: N} 角标用
```

## 六、在线检测

```
OnlineRouteService:
  → serverId + 本机在线数
  → Redis pub/sub 路由表 → 多实例时对方不在本机也能推送
```
