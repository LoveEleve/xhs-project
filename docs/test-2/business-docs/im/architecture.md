# my-xhs-im 架构分析

## 一、服务拓扑

```
端口: 19014
JVM:  -Xms256m -Xmx256m, -Djwt.secret >= 256-bit
日志: /tmp/r_im.log
SkyWalking: my-xhs-im → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、WebSocket 消息路由

```
发送(sender → receiver):
  WebSocket text message {"receiverId":B, "content":"hello", "msgType":1}
  → ChatService.sendMessage(senderId, receiverId, content, msgType)
    → MySQL INSERT t_chat_message
    → UPDATE/UPSERT t_chat_user_relation (last_content/unread_count/updated_at)
    → Redis PUBLISH myxhs:im:route:{receiverId} message(JSON)

接收(receiver):
  所有IM实例 SUBSCRIBE myxhs:im:route:{receiverId}
  → 该用户所在的实例收到消息 → WebSocketHandler.sendToUser(receiverId)
  → 不在线: 消息已存MySQL，下次拉取
```

## 三、两阶段连接

```
Phase 1: HTTP POST /api/im/ws/ticket + X-User-Id
  → JwtUtil.generateToken(userId, "ws_ticket", 5*60*1000)
  → 短期JWT避免WS URL泄露长期Token

Phase 2: ws://host/api/im/ws?ticket=xxx
  → WebSocketConfig → WebSocketHandler → JWT解析 → userId
  → OnlineRouteService.register(userId, channel)
```

## 四、Redis pub/sub 路由表

```
Pattern: myxhs:im:route:{userId}

多实例场景:
  Instance-1: userA 在线 → SUB myxhs:im:route:userA
  Instance-2: userB 在线 → SUB myxhs:im:route:userB
  → userB → userA: PUB myxhs:im:route:userA → Instance-1 收到 → push to userA

心跳: WebSocketHandler 每30s发送 PING/pong
离线检测: 超时关闭 → OnlineRouteService.unregister
```

## 五、会话关系表

```
t_chat_user_relation(user_id, peer_id, last_content, last_msg_type, unread_count, updated_at):
  → 每对用户一条记录(双向)
  → 发消息时 UPSERT: ON DUPLICATE KEY UPDATE last_content/updated_at
  → 对方的 unread_count+1
  → 标记已读: UPDATE unread_count=0 WHERE user_id=? AND peer_id=?
```
