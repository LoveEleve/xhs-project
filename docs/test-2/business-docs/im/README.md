# my-xhs-im 即时通讯服务

> 6 REST端点+WebSocket | ImController | Redis pub/sub路由 | JWT ticket 5min

---

## 架构概览

```
客户端:
  REST POST /api/im/ws/ticket → 获取JWT ticket(5min)
  WS   ws://host/api/im/ws?ticket=xxx → WebSocket连接

服务端:
  WebSocketHandler → OnlineRouteService → Redis pub/sub myxhs:im:route:
  → ChatService → MySQL my_xhs_im库 → t_chat_message/t_chat_user_relation
```

## 端点清单

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| W01 | POST | `/api/im/ws/ticket` | WebSocket ticket签发(JWT 5min) |
| W02 | GET | `/api/im/conversations` | 会话列表(分页) |
| W03 | GET | `/api/im/messages/{peerId}` | 聊天记录(分页) |
| W04 | POST | `/api/im/read/{peerId}` | 标记已读 |
| W05 | GET | `/api/im/unread-count` | 总未读数 |
| W06 | GET | `/api/im/online-count` | 在线人数(X-Admin-Call) |
| WS | WS | `/api/im/ws` | WebSocket实时消息(ticket鉴权) |

## 两步法鉴权

```
Step 1: POST /api/im/ws/ticket + Header X-User-Id
  → JwtUtil.generateToken(userId, "ws_ticket", 5min) → 返回 {ticket: "jwt..."}

Step 2: ws://host/api/im/ws?ticket={ticket}
  → WebSocketConfig:37 → WebSocketHandler → JWT验证 → userId → 建立连接
  → Redis pub/sub 注册: myxhs:im:route:{userId} ← 多点广播路由
```

## MySQL

| 表 | 库 | 说明 |
|------|------|------|
| t_chat_message | my_xhs_im | 消息记录(sender_id/receiver_id/content/msg_type) |
| t_chat_user_relation | my_xhs_im | 会话关系(peer_id/last_content/unread_count/updated_at) |

> ⚠️ IM表在独立的 `my_xhs_im` 库，非 `my_xhs_user` 库。

## ⚠️ 启动条件

`-Djwt.secret` 必须 >= 256位(32字节)，否则 JwtUtil 创建 ticket 失败。
