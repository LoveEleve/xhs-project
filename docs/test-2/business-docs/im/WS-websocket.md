# WS: WebSocket 实时消息 — ws://host/api/im/ws

## § 源码分析

- **Config**: `WebSocketConfig.java:37` → `WebSocketConfigurer.registerWebSocketHandlers()` → 注册 `/api/im/ws`
- **Handler**: `WebSocketHandler.java` → `TextWebSocketHandler`
  - `afterConnectionEstablished(session)`: JWT ticket解析 → userId → OnlineRouteService.register
  - `handleTextMessage(session, textMessage)`: JSON解析 → ChatService.sendMessage
  - `afterConnectionClosed(session, status)`: OnlineRouteService.unregister
- **鉴权**: `beforeHandshake` → URL参数 `?ticket=jwt...` → JWT验证 → subject=userId → 写入attributes
- **心跳**: `TextWebSocketHandler` 每30s ping/pong
- **下游**: MySQL my_xhs_im + Redis pub/sub `myxhs:im:route:{userId}`

## § 业务逻辑

```
Phase 1: POST /api/im/ws/ticket → JWT ticket(5min)
Phase 2: ws://host/api/im/ws?ticket={ticket} → WebSocket连接
  → afterConnectionEstablished: ticket验证+userId注册
  → handleTextMessage: {"receiverId":B, "content":"hello", "msgType":1}
    → ChatService.sendMessage(A, B, content, msgType)
      → INSERT t_chat_message
      → UPSERT t_chat_user_relation(A→B) + UPSERT t_chat_user_relation(B→A, unread_count+1)
      → Redis PUBLISH myxhs:im:route:B → 对方实例收到 → sendToUser(B)
  → afterConnectionClosed: OnlineRouteService.unregister(userId)
```

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| WS ticket有效 | JWT解析+5min未过期 | 连接被拒绝 |
| JWT secret配置 | 系统属性 `jwt.secret` >= 256位 | 启动失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| WS连接 | `websocat ws://host/api/im/ws?ticket=$TICKET` | 握手成功 |
| MySQL | `SELECT COUNT(*) FROM my_xhs_im.t_chat_message WHERE sender_id=A AND receiver_id=B` | 新增1行 |
| Redis | `redis-cli SUBSCRIBE myxhs:im:route:{B}` | 收到消息 |
| HTTP | `ls execution/im/WS/` 含实时截获的发送/接收验证文件 | 消息到达 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | JWT ticket 5min短期 | ✅ |
| 安全 | WS URL仅暴露短期ticket | ✅ |
| 可靠 | 离线消息存MySQL可拉取 | ✅ |
| 可扩展 | Redis pub/sub支持多实例路由 | ✅ |
| 心跳 | 30s ping/pong心跳保活 | ✅ |

## § curl

```bash
# Step 1: 获取WS ticket
TICKET=$(curl -s -X POST http://localhost:19014/api/im/ws/ticket \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['ticket'])")

# Step 2: WebSocket连接 + 发送消息 (需 websocat 或 wscat)
echo '{"receiverId":1002,"content":"hello from test","msgType":1}' \
  | websocat -n "ws://localhost:19014/api/im/ws?ticket=$TICKET"
```

## § ASCII流转图

```
Phase 1: ws://host/api/im/ws?ticket=jwt...
  → WebSocketConfig.beforeHandshake(uri=ticket) → JWT解析 → userId → {userId}
  → afterConnectionEstablished → OnlineRouteService.register(userId, channel)
  → Redis SUBSCRIBE myxhs:im:route:{userId} ← 接收其他实例转发

Phase 2: {"receiverId":"B","content":"hi","msgType":1}
  → WebSocketHandler.handleTextMessage(text)
  → ChatService.sendMessage(A, B, content, msgType)
    → INSERT my_xhs_im.t_chat_message(sender_id=A, receiver_id=B, content, msgType)
    → UPSERT my_xhs_im.t_chat_user_relation
    → Redis PUBLISH myxhs:im:route:B → 对方实例 → pushToUser(B)

Phase 3: close
  → afterConnectionClosed → OnlineRouteService.unregister(userId) → Redis UNSUBSCRIBE
```
