# my-xhs-im 模块分析

## 1. 模块定位
即时通信域（19014）：WebSocket 实时消息（单聊/群聊路由）、离线消息、ACK/已读/typing、在线路由。含 ImWebSocketHandler、ImHandshakeInterceptor、ChatService、MessagePersistService、OnlineRouteService、ImRouteSubscriber、ImConsistentHashLoadBalancer（一致性哈希路由实例）。

## 2. 代码事实
- 18 个 java：1 Controller（REST：历史/在线数）、WebSocket 层 3、Service 3（Chat/MessagePersist/OnlineRoute）、RouteSubscriber、LoadBalancer、Mapper 2（ChatMessage/ChatUserRelation）
- WebSocket 握手 ticket 两步鉴权（gateway 白名单 /api/im/ws）

## 3. 核心链路
```text
WS连接 → HandshakeInterceptor(ticket鉴权) → ImWebSocketHandler
→ ChatService.handleChat: 消息路由(在线直发/离线存储) + 持久化(MessagePersistService)
→ 接收方在线: 直推; 离线: storeOfflineMessage → 上线 pushOfflineMessages
ACK/已读/typing 状态同步
路由: ImConsistentHashLoadBalancer 一致性哈希 + ImRouteSubscriber 订阅路由变化
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| MySQL | t_chat_message、t_chat_user_relation |
| Redis | 在线路由表、离线消息索引、typing 状态 |
| WebSocket | 多实例间路由（一致性哈希选实例） |
| MQ | （可选路由变更订阅 ImRouteSubscriber） |

## 5. 关键决策
- 一致性哈希负载均衡：同一对用户会话固定实例，避免跨实例路由
- 两步鉴权：握手 ticket 校验（WS 无法带 Authorization）
- 离线消息补偿：上线批量拉取

## 6. 运行态验证
- **已实测**：REST 全通（ticket 签发/会话/历史/未读/已读 200）；WS 握手鉴权 fail-closed（无 ticket im 日志"缺少 ticket 参数"拒绝，合法 ticket 101）
- 消息路由/持久化/离线消息需双端 WS 客户端真实收发，未断言

## 7. 鉴权基础
- REST 接口需 JWT；WS 握手 ticket 两步鉴权；online-count 公开

## 8. 风险
- WebSocket 连接数/内存（session 管理）
- 一致性哈希迁移时连接重路由
- 消息持久化与实时投递的一致窗口

## 9. 覆盖对账
- WebSocket 层/ChatService/路由机制结构已读；持久化细节标注
- Mapper/Entity 简单类未逐行
- **运行态已实测**（REST 全通 + WS 握手鉴权 fail-closed；消息路由待双端 WS 客户端）
