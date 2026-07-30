# 12-im 即时通讯服务 — 架构文档

> 端口：19014 | 数据库：my_xhs_im | 更新时间：2026-07-29

---

## 1. 模块定位

IM 服务是 my-xhs 平台的实时私信通讯中枢，支持用户间点对点文本消息收发、在线状态管理、会话列表和历史消息查询。

核心能力：
- **WebSocket 长连接**：Ticket 两步法认证，一致性 Hash 路由
- **写扩散消息存储**：共享存储 + conversationId 分片，单条消息只存一份
- **跨实例消息路由**：Redis Pub/Sub 精准投递，零广播浪费
- **未读计数**：Redis Hash 原子计数
- **离线消息**：Redis Sorted Set 暂存 + Lua 原子裁剪 + 上线批量推送

---

## 2. 架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                               客户端（浏览器/App）                          │
│                              │           │                                │
│                REST API      │     WS    │                                │
│                (HTTP)        │   Websock │                                │
│                              ▼           ▼                                │
│              ┌─────────────────────────────┐                              │
│              │         Gateway (19000)      │                              │
│              │         HMAC Signature       │                              │
│              └──────────┬──────────────────┘                              │
│                         │ /api/im/*                                       │
│              ┌──────────▼──────────────────┐                              │
│              │      IM 实例 A (19014)       │                              │
│              │                              │                              │
│    ┌─────────┼───────────────┐              │                              │
│    │  ImController (REST)   │              │                              │
│    │  POST  /ws/ticket      │              │                              │
│    │  GET   /conversations  │              │                              │
│    │  GET   /messages/{id}  │              │                              │
│    │  POST  /read/{id}      │              │                              │
│    │  GET   /unread-count   │              │                              │
│    └─────────┬──────────────┘              │                              │
│              │                             │                              │
│    ┌─────────▼──────────────────────────┐  │                              │
│    │  ImWebSocketHandler (WS)           │  │                              │
│    │  /api/im/ws?ticket=xxx             │  │                              │
│    │  ┌──────┐ ┌──────┐ ┌──────┐       │  │                              │
│    │  │CHAT  │ │ACK   │ │READ  │       │  │                              │
│    │  │TYPING│ │PING  │ │LOGOUT│       │  │                              │
│    │  └──┬───┘ └──┬───┘ └──┬───┘       │  │                              │
│    └─────┼────────┼────────┼────────────┘  │                              │
│          │        │        │               │                              │
│    ┌─────▼────────▼────────▼────────────┐  │                              │
│    │          ChatService               │  │                              │
│    │  handleChat → 持久化 + 路由 + 未读  │  │                              │
│    │  handleRead → DB清零 + Redis清零    │  │                              │
│    │  pushOffline → ZRange + 批量查询    │  │                              │
│    └──────────┬─────────────────────────┘  │                              │
│               │                            │                              │
│    ┌──────────▼─────────────────────────┐  │                              │
│    │    MessagePersistService           │  │                              │
│    │    @Transactional 消息+会话写扩散   │  │                              │
│    └──────────┬─────────────────────────┘  │                              │
│               │                            │                              │
│    ┌──────────▼────────┐  ┌───────────────▼───────────────┐               │
│    │     MySQL         │  │    OnlineRouteService          │              │
│    │ my_xhs_im (13306) │  │  im:route:{uid} → serverId     │              │
│    │ + 从库 (13310)    │  │  Lua 原子注销（防误删）        │              │
│    └───────────────────┘  └───────────────┬───────────────┘               │
│                                           │                              │
│    ┌──────────────────────────────────────▼──────────────────────────────┐│
│    │                     Redis (Business 16381)                          ││
│    │  im:route / im:online / im:unread(Hash) / im:offline(ZSet)         ││
│    │  im:seq (INCR)  /  Pub/Sub: im:route:{serverId}                    ││
│    └─────────────────────────────────────────────────────────────────────┘│
│                                                                           │
│    ┌──────────────────────────────────────────────────────────────────┐   │
│    │              ImRouteSubscriber (Redis Pub/Sub)                   │   │
│    │  本实例专属 Channel → RouteMessage → pushToUser                  │   │
│    └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 源码清单

| 文件 | 职责 |
|---|---|
| `ImApplication.java` | 启动类，@EnableDiscoveryClient + @EnableFeignClients |
| `controller/ImController.java` | REST API（Ticket/会话/消息/已读/未读/在线） |
| `handler/ImWebSocketHandler.java` | WebSocket 消息处理（CHAT/ACK/READ/TYPING/PING/LOGOUT） |
| `handler/ImHandshakeInterceptor.java` | WebSocket 握手拦截——Ticket JWT 校验 |
| `service/ChatService.java` | 核心业务编排（发送/已读/离线消息/回执） |
| `service/MessagePersistService.java` | 消息持久化 + 会话写扩散（独立 @Transactional Bean） |
| `service/OnlineRouteService.java` | 在线路由管理（Redis 注册/续期/注销 + Lua 防误删） |
| `subscriber/ImRouteSubscriber.java` | Redis Pub/Sub 跨实例路由订阅器 |
| `loadbalancer/ImConsistentHashLoadBalancer.java` | 一致性 Hash 负载均衡（FNV-1a，150 vnodes/instance） |
| `resources/application.yml` | 主配置（端口/Redis/Nacos/Sentinel/Feign/WebSocket/RocketMQ） |
| `resources/application-datasource.properties` | 数据源配置（主库 13306 + 从库 13310 读写分离） |
| `resources/logback-spring.xml` | 日志配置（控制台 + 滚动文件 + Logstash JSON + SkyWalking） |
| `Dockerfile` | 容器化构建 |
| `config/WebSocketConfig.java` | WebSocket 注册配置（path/allowed-origins） |
| `mapper/ChatMessageMapper.java` | 消息表 Mapper（MyBatis-Plus BaseMapper） |
| `mapper/ChatUserRelationMapper.java` | 会话关系表 Mapper |
| `entity/ChatMessage.java` | 消息实体（t_chat_message） |
| `entity/ChatUserRelation.java` | 会话关系实体（t_chat_user_relation） |
| `dto/ImMessage.java` | WebSocket 消息协议 DTO（客户端→服务端） |
| `dto/ImMessageVO.java` | 消息 VO（对外暴露） |
| `dto/ConversationVO.java` | 会话列表 VO |
| `dto/RouteMessage.java` | 跨实例路由消息 DTO |

---

## 4. 数据模型

### 4.1 t_chat_message（消息表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花算法 ID |
| conversation_id | BIGINT NOT NULL | 会话ID = min(A,B)*31 + max(A,B)。SQL 注释沿用旧版位运算 `min<<32|max`，代码实际已修复为乘法防溢出 |
| sender_id | BIGINT NOT NULL | 发送者 ID |
| receiver_id | BIGINT NOT NULL | 接收者 ID |
| content | VARCHAR(2048) NOT NULL | 消息内容 |
| msg_type | TINYINT NOT NULL DEFAULT 0 | 0-文本 1-图片 2-系统消息 |
| seq_no | BIGINT | 会话内序列号（Redis INCR 生成） |
| is_read | TINYINT NOT NULL DEFAULT 0 | 0-未读 1-已读 |
| created_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

索引：
- `idx_conversation (conversation_id)`
- `idx_conversation_seq (conversation_id, seq_no)` — 历史消息查询
- `idx_sender (sender_id)`
- `idx_receiver (receiver_id)`
- `idx_created_at (created_at)`

### 4.2 t_chat_user_relation（用户会话关系表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT NOT NULL PK | 雪花算法 ID |
| user_id | BIGINT NOT NULL | 用户 ID |
| peer_id | BIGINT NOT NULL | 对方用户 ID |
| conversation_id | BIGINT NOT NULL | 会话 ID |
| last_message_id | BIGINT | 最后一条消息 ID |
| last_content | VARCHAR(2048) | 最后消息内容 |
| last_msg_type | TINYINT | 最后消息类型 |
| unread_count | INT NOT NULL DEFAULT 0 | 未读计数 |
| is_deleted | TINYINT NOT NULL DEFAULT 0 | 逻辑删除（MyBatis-Plus @TableLogic） |
| created_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | 更新时间 |

索引：
- `uk_user_peer (user_id, peer_id)` UNIQUE — 每对用户只有一条关系记录
- `idx_user_id (user_id)` — 会话列表查询
- `idx_updated_at (updated_at)` — 排序

### 4.3 Redis Key 设计

| Key 模式 | 类型 | TTL | 说明 |
|---|---|---|---|
| `im:route:{userId}` | String (serverId) | 90s（心跳续期 3 倍间隔） | 在线路由 |
| `im:online:{userId}` | String ("1") | 90s | 在线状态 |
| `im:unread:{userId}` | Hash (peerId→count) | 永久 | 分会话未读数 |
| `im:offline:{userId}` | ZSet (msgId→timestamp) | 7 天 | 离线消息 ID 暂存 |
| `im:seq:{conversationId}` | String (自增) | 永久 | 序列号生成器 |
| `im:route:{serverId}` | Pub/Sub Channel | — | 跨实例消息路由 |

---

## 5. API 端点

### 5.1 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/im/ws/ticket` | 获取 WebSocket Ticket（需 X-User-Id Header） |
| GET | `/api/im/conversations` | 会话列表（分页） |
| GET | `/api/im/messages/{peerId}` | 历史消息（分页） |
| POST | `/api/im/read/{peerId}` | 标记与某人的会话已读 |
| GET | `/api/im/unread-count` | 总未读消息数 |
| GET | `/api/im/online-count` | 本实例在线连接数 |

### 5.2 WebSocket 协议

**连接**：`ws://host:19014/api/im/ws?ticket={JWT}`

**消息协议**（JSON，客户端→服务端）：

| type | 说明 | 关键字段 |
|---|---|---|
| CHAT | 发送聊天消息 | `to`, `content`, `msgType` |
| ACK | 确认收到消息 | `msgId` |
| READ | 标记已读回执 | `peerId`, `msgId` |
| TYPING | 输入状态通知 | `peerId` |
| PING | 心跳 | 无 |
| LOGOUT | 主动断开 | 无 |

**推送消息**（服务端→客户端）：

| type | 说明 | 关键字段 |
|---|---|---|
| ACK | 消息发送成功确认 | `msgId`, `timestamp` |
| NACK | 消息发送失败 | `msgId`, `reason` |
| CHAT | 收到新消息 | `from`, `content`, `msgType`, `msgId`, `seqNo`, `timestamp` |
| OFFLINE | 离线消息批量推送 | `msgs[]`, `total` |
| READ_NOTIFY | 对方已读回执 | `peerId` |
| TYPING | 对方正在输入 | `peerId`, `isTyping` |
| PONG | 心跳回复 | 无 |

---

## 6. 核心流程

### 6.1 WebSocket 连接流程（两步法认证）

```
前端                       IM 实例
  │                          │
  │ POST /api/im/ws/ticket   │
  │ Header: X-User-Id: 123   │
  │─────────────────────────>│
  │                          │ JWT.generate(uid, "ws_ticket", 5min)
  │ {"ticket":"eyJ..."}      │
  │<─────────────────────────│
  │                          │
  │ ws /api/im/ws?ticket=... │
  │─────────────────────────>│
  │                          │ JWT.parse → type=ws_ticket 校验
  │                          │ → userId=123 注入 session
  │ 连接建立                 │
  │<─────────────────────────│
  │                          │ Redis: SET im:route:123 = serverId EX 90
  │                          │ Redis: SET im:online:123 = "1" EX 90
  │                          │ 踢掉旧连接（如有）
  │                          │ 推送离线消息（如有）
```

### 6.2 聊天消息发送流程

```
发送方 A                     IM 实例
   │                          │
   │ CHAT {to:B, content}     │
   │─────────────────────────>│
   │                          │ ① IdWorker → msgId
   │                          │ ② conversationId = min(A,B)*31 + max(A,B)
   │                          │ ③ INCR im:seq:{convId} → seqNo
   │                          │ ④ @Transactional:
   │                          │    INSERT t_chat_message
   │                          │    UPSERT A 的会话（unread不变）
   │                          │    UPSERT B 的会话（unread+1）
   │                          │ ⑤ 查 im:route:B
   │                          │    ├─ 同实例 → pushToUser(B)
   │                          │    └─ 跨实例 → PUBLISH im:route:{targetServerId}
   │                          │ ⑥ HINCRBY im:unread:B A 1
   │                          │ ⑦ 发送 ACK 给 A
   │ ACK {msgId, timestamp}   │
   │<─────────────────────────│
```

### 6.3 已读回执流程（handleRead）

```
接收方 B                     IM 实例                        Redis
   │                          │                              │
   │ READ {peerId:A, msgId}   │                              │
   │─────────────────────────>│                              │
   │                          │ UPDATE t_chat_user_relation  │
   │                          │ SET unread_count=0           │
   │                          │ WHERE user_id=B, peer_id=A   │
   │                          │─────────────────────────────>│
   │                          │ HSET im:unread:B A 0        │
   │                          │─────────────────────────────>│
   │                          │ 查 im:route:A → serverId     │
   │                          │ ├─ 同实例 → pushToUser(A)    │
   │                          │ └─ 跨实例 → PUBLISH im:route │
   │                          │    READ_NOTIFY {peerId=B}    │
   │  READ_NOTIFY{peerId:B}   │                              │
   │<─────────────────────────│                              │
```

### 6.4 输入状态通知流程（handleTyping）

```
发送方 A                     IM 实例
   │                          │
   │ TYPING {peerId:B}        │
   │─────────────────────────>│
   │                          │ 查 im:route:B
   │                          │ ├─ 同实例 → pushToUser(B)
   │                          │ │  TYPING {peerId:A, isTyping:true}
   │                          │ └─ 跨实例 → PUBLISH im:route:{targetSvr}
   │                          │    内容复用（msgType=98）
   │                          │ 无持久化，无 DB 写入
```

### 6.5 离线消息机制

**存储**（Lua 原子操作）：
```
ZADD im:offline:{userId} timestamp msgId
ZCARD → 超过 1000 条 → ZREMRANGEBYRANK 裁剪
EXPIRE 7 天
```

**推送**（用户上线时）：
```
ZRANGE im:offline:{userId} 0 999 → msgIds
SELECT_BATCH_IDS(msgIds) FROM t_chat_message
按 seq_no 排序 → 批量推送 OFFLINE 事件
客户端逐条 ACK → ZREM 移除
```

---

## 7. 幂等设计

| 场景 | 机制 |
|---|---|
| 消息重复投递 | msgId + ACK 回执，客户端确认后 ZREM 离线 Set |
| 离线消息重复推送 | ZRANGE 不删除，客户端 ACK 逐条删除 |
| 路由并发注册 | ConcurrentHashMap.put 覆盖旧连接，closeQuietly 非阻塞 |

消息表无业务唯一键——msgId 雪花算法主键，不可能重复 INSERT。

---

## 8. 配置要点

| 配置项 | 值 | 说明 |
|---|---|---|
| 端口 | 19014 | |
| Tomcat 线程 | max=150, min-spare=15 | WebSocket 不占用线程 |
| 连接数上限 | 10000 | |
| MySQL 主库 | 21.130.247.89:13306 | my_xhs_im |
| MySQL 从库 | 21.130.247.89:13310 | 读写分离 |
| Redis Sentinel | 26379/26380/26381 | master=mymaster |
| Redis Business | 16381 | noeviction |
| WebSocket path | /api/im/ws | |
| WebSocket 心跳间隔 | 30000ms | PING 触发路由续期，TTL=心跳×3（90s） |
| WebSocket 最大连接 | 100000 | 单机上限 |
| WebSocket 空闲超时 | 300000ms | 5分钟无消息自动断开 |
| 一致性 Hash 虚拟节点 | 150 vnodes/instance | FNV-1a 哈希算法 |
| 一致性 Hash 激活 | `spring.cloud.loadbalancer.configurations=my-xhs-im-consistent-hash` | 需显式配置 |
| RocketMQ NS | 9876;9877 | 依赖在 pom 中但未使用 |
| Nacos | 18848 | namespace=my-xhs |
| Feign | connect-timeout=500ms, read-timeout=2000ms | 依赖在 pom 中但未使用 |

---

## 9. 依赖关系

### 上游依赖
- **MySQL (13306/13310)**：消息和会话持久化
- **Business Redis (16381)**：路由/未读/离线/序列号/Pub/Sub
- **Nacos**：服务注册

### 下游消费者
- **前端客户端**：WebSocket + REST API

### 不依赖其他微服务
IM 模块不调用其他微服务的 Feign 接口。跨实例消息路由走 Redis Pub/Sub，不走 HTTP 或 MQ。

**依赖项说明**：pom.xml 中声明了 `spring-cloud-starter-openfeign` 和 `rocketmq-spring-boot-starter`，但代码中**未使用**（RocketMQ 无 Consumer/Producer，OpenFeign 无 Client）。这两个依赖继承自父 POM 的 BOM 管理，对启动无影响。

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| WebSocket 实现 | Tomcat NIO（Spring WebSocket） | 与 MyBatis-Plus 阻塞 JDBC 兼容 |
| 消息存储 | 写扩散共享存储（一份消息） | 节省存储，conversationId 分片友好 |
| 跨实例路由 | Redis Pub/Sub 专属 Channel | 精准投递，零广播浪费 |
| 用户路由 | 一致性 Hash（FNV-1a, 150 vnodes） | 同用户始终路由到同实例 |
| 离线消息 | Lua ZSet 原子操作 | 原子性保障+阈值裁剪 |
| 会话ID | min*31+max | 可复现，对比位运算无溢出风险 |

---

## 11. WebSocket 消息类型

| type | 方向 | 说明 |
|---|---|---|
| CHAT | C→S | 发送消息 |
| ACK | S→C / C→S | 服务端确认 / 客户端确认离线 |
| NACK | S→C | 发送失败 |
| READ | C→S | 已读回执 |
| READ_NOTIFY | S→C | 对方已读 |
| TYPING | 双向 | 输入状态 |
| PING/PONG | 双向 | 心跳保活（30s） |
| OFFLINE | S→C | 批量离线消息 |
| LOGOUT | C→S | 主动断开 |

---

## 12. 测试场景建议

### 基础功能
1. Ticket 获取 → WebSocket 连接建立
2. A→B 发消息 → B 收到 + A 收 ACK
3. 历史消息查询（分页）
4. 会话列表查询
5. 已读回执 → 对方收 READ_NOTIFY
6. 未读计数查询

### 边界
7. 空内容/超长内容 → NACK
8. 给自己发消息 → NACK
9. 无效 Ticket → 握手拒绝
10. 离线消息 → B 离线时 A 发消息 → B 上线后收到 OFFLINE

### 多实例
11. 跨实例消息投递（A 在实例1，B 在实例2）
12. 一致性 Hash 路由验证（同一 userId 始终命中同一实例）

### 心跳保活
13. PING/PONG 验证（30s 间隔，路由 TTL 续期）
14. 连接断开 → Redis 路由自动注销 → 对方发消息立即进入离线

### 并发
15. 多用户同时互发消息，观察 seqNo 不冲突
16. 同一用户多设备登录 → 旧连接被踢下线
