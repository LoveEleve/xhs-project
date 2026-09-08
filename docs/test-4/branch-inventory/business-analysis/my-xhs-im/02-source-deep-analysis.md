# my-xhs-im 模块深度分析

## 1. 当前模块定位

im（19014）是即时通信服务：WebSocket 实时消息（单聊路由）、ACK/已读/typing 状态、离线消息存储与补发、在线路由。用 Redis pub/sub（`myxhs:im:route:*`）做跨实例路由，一致性哈希选实例。REST 提供会话/历史/未读/在线数。

## 2. 当前代码事实

- 启动入口 `ImApplication`；Controller：ImController（ws/ticket、conversations、messages、read、unread-count、online-count）。
- WebSocket：ImWebSocketHandler、ImHandshakeInterceptor（ticket JWT 两步鉴权）、WebSocketConfig。
- Service：ChatService、MessagePersistService、OnlineRouteService。
- 路由：ImConsistentHashLoadBalancer、ImRouteSubscriber。
- Mapper：ChatMessageMapper、ChatUserRelationMapper。
- 实体：ChatMessage、ChatUserRelation、ImMessage/ImMessageVO/RouteMessage/ConversationVO。

## 3. 关键业务链路与源码流转

### 3.1 消息发送

```text
WS 连接 → HandshakeInterceptor(ticket 鉴权) → ImWebSocketHandler
→ ChatService.handleChat(senderId, ImMessage, senderSession)
  - 校验/幂等（本地 msgId）
  - 接收方在线: 同实例直推 or 跨实例 Redis pub/sub(myxhs:im:route:{serverId}) 路由
  - 接收方离线: storeOfflineMessage
  - MessagePersistService 持久化 t_chat_message
  - 发送方 ACK
```

### 3.2 状态与离线

```text
handleAck: 标记已送达
handleRead: 已读回执 + 会话已读
handleTyping: typing 实时状态
pushOfflineMessages: 上线后批量拉取离线消息
```

### 3.3 路由

```text
ImConsistentHashLoadBalancer: 一致性哈希，同一对用户会话固定实例
ImRouteSubscriber: 订阅路由变化
Redis pub/sub myxhs:im:route:{serverId}: 跨实例消息投递
```

## 4. 数据流转

| 中间件 | key/表/topic | 说明 |
|---|---|---|
| Redis | `myxhs:im:route:{serverId}` | 跨实例 pub/sub 路由 |
| Redis | 在线路由表、离线消息索引 | 在线状态/离线补偿 |
| MySQL | t_chat_message、t_chat_user_relation | 消息/关系持久化 |
| WebSocket | 长连接 | 实时双向 |

## 5. 跨模块与分布式行为

- 一致性哈希保证同会话固定实例，避免跨实例消息乱序。
- 跨实例经 Redis pub/sub 投递；离线走离线消息补发。
- ticket 两步鉴权（WS 无法带 Authorization 头）。

## 6. 性能与工程质量

- Tomcat NIO WebSocket（不占用线程，注释声明 10 万连接）。
- 离线消息索引 + 上线批量拉取。
- 一致性哈希减少重路由。

## 7. 鉴权基础检查

- REST 需 JWT；WS 握手 ticket（JWT 5min）鉴权，无 ticket/非法 ticket 拒绝。
- online-count 公开。

## 8. 当前分支/改动点

- 字符串 ID 兼容、ticket 两步鉴权、一致性哈希路由。

## 9. 风险与测试重点

- 代码：路由跨实例投递、离线补发边界。
- 业务：消息幂等、已读/未读、typing。
- 分布式：一致性哈希重路由、Redis pub/sub 可靠性。
- 性能：WS 连接数、消息持久化与实时投递窗口。

## 10. 覆盖对账

WebSocket 层/ChatService/路由机制已深读；持久化细节、Mapper/实体随调用链核对。
