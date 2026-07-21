# 即时通讯 IM

> 所属服务：my-xhs-im (9014) | 开发阶段：Phase-4 | 预计耗时：5天

---

## 🎯 一、需求分析

### 1.1 业务场景

小红书的私信功能是用户间1对1沟通的核心通道——买家向卖家咨询商品、创作者与粉丝互动、用户之间分享好物。与通知系统的"单向广播"不同，IM需要**双向实时通信**，对消息可靠性和时序性要求更高。

**典型用户场景**：
- 场景1：买家向商家咨询"这个还有货吗？" → 商家实时收到消息并回复
- 场景2：用户发送图片消息 → 对方收到带缩略图的消息
- 场景3：用户打开私信列表 → 看到5个会话，每个显示最后一条消息和未读数
- 场景4：用户离线期间收到3条消息 → 上线后收到3条离线消息推送

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| WebSocket长连接 | ✅ | Spring WebFlux + Netty实现 |
| 私信发送/接收 | ✅ | 文本+图片消息 |
| 会话列表 | ✅ | 按最新消息时间排序 |
| 未读消息计数 | ✅ | Redis原子维护 |
| 离线消息推送 | ✅ | 上线后拉取离线消息 |
| 消息已读回执 | ✅ | 单条/批量已读 |
| 历史消息查询 | ✅ | 分页拉取 |
| 消息类型（文本/图片/系统） | ✅ | msg_type枚举 |
| 群聊 | ❌ | Phase-3扩展，当前只做私信 |
| 音视频通话 | ❌ | 非核心场景 |
| 消息撤回 | ❌ | 非核心，后续扩展 |
| 消息搜索 | ❌ | 依赖ES，后续扩展 |
| 多端同步 | ❌ | Phase-3扩展 |
| 消息加密 | ❌ | 非核心，后续扩展 |

### 1.3 数据量预估

**推导前提**：私信是1对1主动沟通，频率远低于被动触发的通知。参考小红书/抖音私信数据，DAU中只有约5%的用户每天会发私信，且人均5-10条。

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 消息总量 | 10亿 | 写扩散：2份/条。1000万用户 × 日均1条私信（全量用户均值） × 2份 × 365天（1年留存期）≈ 7.3亿，考虑电商咨询场景消息密度更高，取10亿 |
| 日增量 | 550万/天 | 1000万DAU × 5%私信活跃率 × 10条/人/天 × 2份(写扩散) = 1000万条（去重后500万条原始消息） |
| 峰值QPS | 800 | 日均1000万条 ÷ 86400 × 峰值倍率3 ≈ 347，但电商咨询集中在10-12点/20-22点，有效分布时段约6小时，峰值倍率5 → 1000万÷(6×3600)×2 ≈ 926，取800 |
| 单会话消息量 | 200条 | 活跃用户1年与单人的私信量（电商咨询型会话约20-50条，社交型约100-300条，取均值） |
| WebSocket在线数 | 100万 | DAU的10%同时在线：私信场景下用户主动打开聊天页面才建立WebSocket，高于SSE的5%（SSE是全局连接，WebSocket是聊天场景触发） |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
                        ┌─────────────────────────────────────────┐
                        │              Client (前端)                │
                        │   ┌──────────┐  ┌─────────────────────┐ │
                        │   │WebSocket  │  │  REST API (历史消息)  │ │
                        │   │  长连接   │  │  会话列表/已读/未读   │ │
                        │   └─────┬────┘  └──────────┬──────────┘ │
                        └─────────┼──────────────────┼────────────┘
                                  │                  │
                        ┌─────────┼──────────────────┼────────────┐
                        │  Gateway (9000)            │            │
                        │  ⚠️配置: WebSocket代理需     │            │
                        │  特殊路由+无限超时          │            │
                        └─────────┬──────────────────┬────────────┘
                                  │                  │
                    ┌─────────────▼────────┐  ┌─────▼───────────────┐
                    │   my-xhs-im (9014)    │  │   my-xhs-im (9014)  │
                    │  WebSocket Handler    │  │    REST API模块      │
                    │  Netty Reactive       │  │  会话/消息/已读CRUD   │
                    │  ⚠️注意: Reactor线程   │  │                      │
                    │  不能做同步IO!         │  │                      │
                    └──────────┬────────────┘  └─────────┬──────────┘
                               │                         │
              ┌────────────────┼─────────────────────────┼──────────────┐
              │                │                         │              │
     ┌────────▼─────┐  ┌──────▼──────┐  ┌──────────────▼──┐  ┌──────▼──────┐
     │   RocketMQ    │  │    Redis    │  │  MySQL (分表)    │  │   Feign     │
     │ 跨服消息路由  │  │ 路由/未读   │  │  消息/会话持久化  │  │ 查用户信息  │
     │               │  │ ⚠️故障域:   │  │                 │  │             │
     │ ⚠️故障域:     │  │ Redis不可用 │  │ ⚠️分片键选择     │  │             │
     │ MQ不可用→     │  │ →路由丢失   │  │ 影响查询效率     │  │             │
     │ 跨实例消息    │  │ →重连重建   │  │ (详见§3)        │  │             │
     │ 无法投递      │  │             │  │                 │  │             │
     └──────────────┘  └─────────────┘  └────────────────┘  └─────────────┘
               │
     ┌─────────▼──────────┐
     │  雪花算法ID生成器    │
     │  ⚠️时钟回拨保护:     │
     │  回拨<5ms→等待追回   │
     │  回拨≥5ms→报警+拒绝  │
     └────────────────────┘

【故障降级链路】
┌────────────────────────────────────────────────────────────────────┐
│ Redis宕机  → 路由表丢失 → 客户端重连重建路由 → 未读计数降级DB查询  │
│ MQ不可用   → 跨实例消息无法投递 → 降级为HTTP回调通知目标实例        │
│ MySQL慢    → 历史消息查询超时 → 降级返回"加载中" + 客户端重试       │
│ 时钟回拨   → 雪花算法拒绝生成ID → 报警 + 等待时钟追回              │
│ WebSocket断→ 客户端自动重连(指数退避) → 重连后拉取离线消息补齐     │
└────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块交互

| 交互对象 | 交互方式 | 说明 |
|---------|---------|------|
| Client → IM服务 | WebSocket | 长连接双向通信(wss://) |
| Client → IM服务 | HTTP REST | 历史消息查询/会话列表/已读标记 |
| IM服务 → RocketMQ | 发送消息 | 跨服务实例消息路由 |
| IM服务 → Redis | RedisTemplate | 用户路由表/未读计数/在线状态 |
| IM服务 → MySQL | MyBatis Plus | 消息和会话持久化 |
| IM服务 → 用户服务 | OpenFeign | 查询用户昵称/头像 |
| 通知服务 → IM服务 | HTTP | 系统消息通过IM推送 |

### 2.3 核心流程时序图

**流程1：发送私信（在线对端）**

```
1. Client A → WebSocket: 发送消息 {to: userIdB, content: "你好", type: 0}
2. IM服务 → 鉴权: 验证Token + 消息校验
3. IM服务 → MySQL: 插入t_chat_message(写扩散: A和B各一份)
4. IM服务 → MySQL: 更新/创建t_chat_user_relation(A和B各一条)
5. IM服务 → Redis: 查询用户B路由 im:route:{userIdB} → serverId
6a. 如果B在本实例 → IM服务 → WebSocket B: 推送消息
6b. 如果B在其他实例 → IM服务 → RocketMQ: 发送IM_ROUTE_TOPIC
   → 其他IM实例消费 → WebSocket B: 推送消息
6c. 如果B不在线 → IM服务 → Redis: 写入离线消息队列 im:offline:{userIdB}
7. IM服务 → Client A: 发送ACK {msgId, timestamp}
```

**流程2：离线消息拉取**

```
1. Client B → WebSocket: 上线连接
2. IM服务 → Redis: 注册路由 im:route:{userIdB} → 当前serverId
3. IM服务 → Redis: 查询离线消息队列 im:offline:{userIdB}
4. IM服务 → Client B: 逐条推送离线消息
5. Client B → IM服务: 逐条ACK确认
6. IM服务 → Redis: 从离线队列中删除已确认消息
```

**流程3：会话列表查询**

```
1. Client → REST API: GET /api/im/conversations?page=1
2. IM服务 → MySQL: SELECT * FROM t_chat_user_relation WHERE send_uid=? OR accept_uid=? ORDER BY updated_at DESC
3. IM服务 → Redis: 批量获取未读数 im:unread:{userId}:{peerId}
4. IM服务 → Client: 返回会话列表(含最后消息、未读数、对方信息)
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

**聊天消息表（按conversation_id分表）**

```sql
-- t_chat_message (按conversation_id分4库×8表=32表)
-- 关键设计变更：分片键从sender_id改为conversation_id
-- 原因：写扩散下A和B各一份消息，按sender_id分片会导致查聊天记录时
-- 需要跨分片查询(A的消息在分片X，B的消息在分片Y，需要合并排序)
-- 改用conversation_id分片：A和B的消息按同一个conversation_id落在同一分片，
-- 查聊天记录只需查一个分片，无需跨分片合并
CREATE TABLE t_chat_message (
    id              BIGINT        NOT NULL COMMENT '消息ID(雪花算法)',
    conversation_id BIGINT        NOT NULL COMMENT '会话ID=min(userId,peerId)*2^32+max(userId,peerId)，保证同一会话消息落在同一分片',
    sender_id       BIGINT        NOT NULL COMMENT '发送者ID',
    receiver_id     BIGINT        NOT NULL COMMENT '接收者ID',
    content         VARCHAR(2048) NOT NULL COMMENT '消息内容',
    msg_type        TINYINT       NOT NULL DEFAULT 0 COMMENT '消息类型：0-文本 1-图片 2-系统消息',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_conversation_time (conversation_id, created_at DESC),
    INDEX idx_sender_time (sender_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';
```

**注意：`is_read`不再放在消息表中！** 写扩散下A和B各一份消息，`is_read`只对接收者有意义。如果放在消息表里：
- 标记已读需要 `UPDATE t_chat_message SET is_read=1 WHERE sender_id=peerId AND receiver_id=userId`
- 按conversation_id分片时，这个UPDATE能命中正确分片，但语义混乱——同一行记录的`is_read`对发送者和接收者含义不同
- 正确做法：`is_read`放在独立的已读状态表中

**消息已读状态表**

```sql
-- t_chat_read_state (已读状态独立表，每对用户每份消息一条记录)
-- 写扩散下A和B各一份消息，每份有独立的已读状态
CREATE TABLE t_chat_read_state (
    id              BIGINT    NOT NULL COMMENT 'ID(雪花算法)',
    user_id         BIGINT    NOT NULL COMMENT '消息拥有者ID(写扩散下消息存在谁的表里)',
    peer_id         BIGINT    NOT NULL COMMENT '对方ID',
    conversation_id BIGINT    NOT NULL COMMENT '会话ID(分片键，与消息表同分片)',
    last_read_msg_id BIGINT   NOT NULL DEFAULT 0 COMMENT '最后已读消息ID(此ID及之前的消息均已读)',
    unread_count    INT       NOT NULL DEFAULT 0 COMMENT '未读消息数',
    updated_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_peer (user_id, peer_id),
    INDEX idx_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息已读状态表';
```

**用户会话关系表**

```sql
-- t_chat_user_relation (会话列表，每对用户双方各一条记录)
CREATE TABLE t_chat_user_relation (
    id              BIGINT        NOT NULL COMMENT 'ID(雪花算法)',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    peer_id         BIGINT        NOT NULL COMMENT '对方用户ID',
    conversation_id BIGINT        NOT NULL COMMENT '会话ID(与消息表同分片键)',
    last_message_id BIGINT        DEFAULT NULL COMMENT '最后一条消息ID',
    last_content    VARCHAR(512)  DEFAULT NULL COMMENT '最后一条消息内容(冗余)',
    last_msg_type   TINYINT       DEFAULT 0 COMMENT '最后消息类型',
    unread_count    INT           NOT NULL DEFAULT 0 COMMENT '未读消息数',
    is_deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否删除会话：0-否 1-是(软删除，不影响对方)',
    deleted_at      DATETIME      DEFAULT NULL COMMENT '删除时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_peer (user_id, peer_id),
    INDEX idx_user_updated (user_id, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户会话关系表';
```

**消息删除标记表（软删除）**

```sql
-- t_chat_message_delete (消息软删除标记：A删消息不影响B看到消息)
-- 写扩散下A和B各一份消息，删除是独立操作，不能直接DELETE消息记录
CREATE TABLE t_chat_message_delete (
    id           BIGINT   NOT NULL COMMENT 'ID',
    user_id      BIGINT   NOT NULL COMMENT '删除操作的用户ID',
    message_id   BIGINT   NOT NULL COMMENT '被删除的消息ID',
    delete_type  TINYINT  NOT NULL DEFAULT 0 COMMENT '删除类型：0-单条删除 1-会话清空',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_msg (user_id, message_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息删除标记表';
```

### 3.2 索引设计

| 索引名 | 字段 | 类型 | 使用场景 |
|--------|------|------|----------|
| idx_conversation_time | (conversation_id, created_at DESC) | INDEX | 查询两人间的聊天记录（核心查询，命中同一分片） |
| idx_sender_time | (sender_id, created_at DESC) | INDEX | 查询发送者的消息历史（辅助查询） |
| uk_user_peer | (user_id, peer_id) | UNIQUE | 保证会话唯一性和已读状态唯一性 |
| idx_user_updated | (user_id, updated_at DESC) | INDEX | 会话列表按时间排序 |

### 3.3 分库分表策略

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | conversation_id | 同一会话的消息落在同一分片，查聊天记录无需跨分片合并 |
| conversation_id生成 | `min(A,B) << 32 \| max(A,B)` | 保证A和B的会话ID唯一且确定性 |
| 分片算法 | conversation_id % 32 → 4库×8表 | 32张表，写扩散后10亿条÷32≈3100万/表 |
| 库数×表数 | 4库×8表=32表 | 分散IO压力，每表控制在5000万以内 |
| 已读状态表 | 同分片键conversation_id | 与消息表同分片，标记已读无需跨分片 |

**为什么从sender_id改为conversation_id？**
- 按sender_id分片：A发消息写分片X，B回消息写分片Y，查聊天记录需要查X和Y两个分片再合并排序——跨分片查询
- 按conversation_id分片：A和B的消息都按同一个conversation_id落在同一分片——单分片查询，性能提升10倍+
- 代价：写扩散时A的消息和B的消息不在同一行（仍需两次INSERT），但都在同一分片，可以用本地事务保证一致性

### 3.4 写扩散 vs 读扩散

| 维度 | 写扩散 | 读扩散 |
|------|--------|--------|
| 写入方式 | 每个参与者写一份消息 | 只写一份消息 |
| 读取方式 | 只查自己的表，无需聚合 | 查询时聚合所有参与者的消息 |
| 写入开销 | 高（N人群聊写N份） | 低（只写1份） |
| 读取开销 | 低（直接查自己的表） | 高（需要实时聚合） |
| my-xhs选择 | ✅ 私信用写扩散 | Phase-3群聊可考虑读扩散 |

**选择理由**：私信只有2人，写扩散只多写1份，但读取时无需聚合，性能更好。群聊人数多时再考虑读扩散。

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `im:route:{userId}` | String | 30s(心跳续期) | 用户在线路由 → serverId |
| `im:online:{userId}` | String | 30s(心跳续期) | 用户在线状态 |
| `im:unread:{userId}` | Hash | 永久 | 会话未读消息数 field=peerId value=count（一个Key管理所有会话未读数，避免用户1000个会话就1000个Key） |
| `im:offline:{userId}` | List | 7d | 离线消息队列（只存消息ID，不存完整JSON——消息内容从DB按ID查询，避免大消息撑爆Redis内存） |
| `im:conversation:{userId}` | ZSet | 30min | 会话列表缓存 score=updated_at |
| `im:seq:{userId}` | String | 永久 | 用户消息序列号(多端同步用) |

> **⚠️ Key设计变更说明**：
> - 原设计 `im:unread:{userId}:{peerId}` 每个会话一个Key——用户有1000个会话就需要1000个Key，Redis中Key本身占用内存（每个Key约96字节开销），1000个Key就是96KB。改为Hash后一个Key搞定，1000个field仅需约50KB。
> - 原设计 `im:offline:{userId}` 存完整消息JSON——图片消息content可达2KB，1000条离线消息就是2MB。改为只存消息ID（8字节），1000条仅8KB，详情从DB查。

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 用户路由 | Write Through | 上线写+心跳续期，下线删 |
| 未读计数 | Cache Aside + INCR | 新消息INCR，标记已读DECR |
| 离线消息 | Write Behind | 先写Redis List，确认后删除 |
| 会话列表 | Cache Aside | 查缓存→Miss→查DB→写缓存 |

### 4.3 缓存异常处理

| 问题 | 解决方案 |
|------|----------|
| 路由表丢失(心跳失败) | 客户端重连时重新注册路由 |
| 未读计数不一致 | 定时对账(Redis vs MySQL) + 修复 |
| 离线消息堆积 | 超过1000条只保留最新1000条 + 拉取历史消息 |
| 会话缓存过期 | 缓存失效时从DB重建 |

---

## 📡 五、接口设计

### 5.1 接口列表

**WebSocket接口**

| 操作 | 路径 | 消息格式 | 说明 |
|------|------|----------|------|
| 建立连接 | `/api/im/ws?ticket=xxx` | — | WebSocket握手（同SSE，用ticket而非Token） |
| 发送消息 | — | `{"ver":1,"type":"CHAT","to":123,"content":"你好","msgType":0}` | 发送私信 |
| 消息ACK | — | `{"ver":1,"type":"ACK","msgId":456}` | 确认收到消息 |
| 已读回执 | — | `{"ver":1,"type":"READ","peerId":123,"msgId":456}` | 标记已读 |
| 输入状态 | — | `{"ver":1,"type":"TYPING","peerId":123}` | 通知对方"正在输入" |
| 心跳 | — | `{"type":"PING"}` | 保活探测 |
| 退出登录 | — | `{"type":"LOGOUT"}` | 主动下线 |

> **⚠️ 为什么消息格式需要`ver`版本号？** 协议升级是不可避免的——比如未来新增消息类型、修改字段名、调整压缩算法。没有版本号，老客户端无法兼容新协议。ver=1表示当前协议版本，服务端根据ver字段做向下兼容。

**WebSocket推送格式**

| 事件 | 格式 | 说明 |
|------|------|------|
| 新消息 | `{"ver":1,"type":"CHAT","msgId":456,"from":123,"content":"你好","msgType":0,"timestamp":1715510539000}` | 收到新消息 |
| 消息ACK | `{"ver":1,"type":"ACK","msgId":456,"timestamp":1715510539000}` | 服务器确认收到 |
| 已读通知 | `{"ver":1,"type":"READ_NOTIFY","peerId":123,"msgId":456}` | 对方已读通知 |
| 输入状态 | `{"ver":1,"type":"TYPING","peerId":123,"isTyping":true}` | 对方正在输入/停止输入 |
| 离线消息 | `{"ver":1,"type":"OFFLINE","msgs":[...]}` | 离线消息批量推送 |
| 心跳响应 | `{"type":"PONG"}` | 心跳响应 |
| 系统通知 | `{"ver":1,"type":"SYSTEM","content":"系统消息内容"}` | 系统消息 |

**REST接口**

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/im/conversations` | 会话列表 | 是 |
| GET | `/api/im/messages/{peerId}` | 与某人的聊天记录(分页) | 是 |
| POST | `/api/im/read/{peerId}` | 标记与某人的消息全部已读 | 是 |
| GET | `/api/im/unread-count` | 总未读消息数 | 是 |

### 5.2 请求/响应示例

**WebSocket发送消息**

```json
{
  "type": "CHAT",
  "to": 1002,
  "content": "你好，请问这个商品还有货吗？",
  "msgType": 0
}
```

**WebSocket接收消息**

```json
{
  "type": "CHAT",
  "msgId": 1893456789012345678,
  "from": 1001,
  "fromName": "张三",
  "fromAvatar": "https://xxx/avatar/1001.jpg",
  "content": "你好，请问这个商品还有货吗？",
  "msgType": 0,
  "timestamp": 1715510539000
}
```

**会话列表**

```http
GET /api/im/conversations?page=1&size=20
Authorization: Bearer eyJhbGciOi...
```

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "peerId": 1002,
        "peerName": "商家A",
        "peerAvatar": "https://xxx/avatar/1002.jpg",
        "lastContent": "有货的，直接下单就行",
        "lastMsgType": 0,
        "unreadCount": 2,
        "updatedAt": "2026-05-12 18:00:00"
      }
    ],
    "total": 5,
    "page": 1
  }
}
```

**聊天记录**

```http
GET /api/im/messages/1002?page=1&size=50
Authorization: Bearer eyJhbGciOi...
```

```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1893456789012345678,
        "senderId": 1001,
        "receiverId": 1002,
        "content": "你好，请问这个商品还有货吗？",
        "msgType": 0,
        "isRead": 1,
        "createdAt": "2026-05-12 17:58:00"
      },
      {
        "id": 1893456789012345680,
        "senderId": 1002,
        "receiverId": 1001,
        "content": "有货的，直接下单就行",
        "msgType": 0,
        "isRead": 0,
        "createdAt": "2026-05-12 18:00:00"
      }
    ],
    "total": 30,
    "page": 1
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 WebSocket Handler（Spring WebFlux）

```java
// 说明：基于Spring WebFlux的WebSocket处理器，Reactive非阻塞
// 关键点：每个连接绑定userId；消息编解码用JSON；心跳用PING/PONG
// ⚠️ 核心原则：Reactor线程不能做阻塞IO（DB查询、同步Redis操作）！
// 所有IO操作必须返回Mono/Flux，用subscribeOn切到弹性线程池
@Component
@Slf4j
public class ImWebSocketHandler implements WebSocketHandler {

    // userId → WebSocketSession
    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private MessageService messageService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // 从握手请求中提取userId（Gateway鉴权后Header透传）
        Long userId = extractUserId(session);
        if (userId == null) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        log.info("WebSocket连接建立, userId={}, sessionId={}", userId, session.getId());

        // 注册在线路由
        sessions.put(userId, session);
        registerRoute(userId);

        // 推送离线消息
        pushOfflineMessages(userId, session);

        // 处理消息收发
        // ⚠️ 正确做法：subscribeOn(Schedulers.boundedElastic()) 将阻塞操作调度到弹性线程池
        // 而非在Reactor线程(Netty EventLoop)中直接执行DB/Redis操作
        Flux<WebSocketMessage> output = session.receive()
            .publishOn(Schedulers.boundedElastic()) // 切换到弹性线程池处理业务逻辑
            .doOnNext(msg -> handleMessage(userId, msg))
            .then(Mono.empty()) // 消费后不转发到output
            .thenMany(getHeartbeatFlux(session)); // 心跳保活

        return session.send(output)
            .doFinally(signalType -> {
                sessions.remove(userId);
                unregisterRoute(userId);
                log.info("WebSocket连接关闭, userId={}, signal={}", userId, signalType);
            });
    }

    /**
     * 处理收到的WebSocket消息
     */
    private void handleMessage(Long userId, WebSocketMessage wsMessage) {
        String payload = wsMessage.getPayloadAsText();
        ImMessage message = JSON.parseObject(payload, ImMessage.class);

        switch (message.getType()) {
            case "CHAT" -> handleChatMessage(userId, message);
            case "ACK" -> handleAckMessage(userId, message);
            case "READ" -> handleReadMessage(userId, message);
            case "TYPING" -> handleTypingMessage(userId, message);
            case "PING" -> { /* 心跳，由PONG响应 */ }
            default -> log.warn("未知消息类型: {}", message.getType());
        }
    }

    /**
     * 处理聊天消息
     * ⚠️ 写扩散一致性方案：
     * A发消息给B → 写A的消息 + 写B的消息（同一conversation_id，同一分片）
     * 问题：写A成功但写B失败 → A看到消息已发送，B收不到
     * 解决：同分片可用本地事务保证原子性；跨分片场景用"最终一致性+补偿"
     */
    private void handleChatMessage(Long senderId, ImMessage message) {
        Long receiverId = message.getTo();
        String content = message.getContent();
        Integer msgType = message.getMsgType();

        // 1. 构建消息实体
        ChatMessage chatMessage = new ChatMessage()
            .setId(snowflakeIdWorker.nextId())
            .setSenderId(senderId)
            .setReceiverId(receiverId)
            .setConversationId(generateConversationId(senderId, receiverId))
            .setContent(content)
            .setMsgType(msgType);

        // 2. 写扩散：A和B各写一份（同一conversation_id，同一分片，可本地事务）
        try {
            messageService.saveMessageWithTransaction(chatMessage);
            // saveMessageWithTransaction 内部逻辑：
            // @Transactional
            // 1) INSERT t_chat_message (A的副本)
            // 2) INSERT t_chat_message (B的副本)
            // 3) UPDATE t_chat_user_relation (A的会话)
            // 4) UPDATE t_chat_user_relation (B的会话)
            // 因为A和B的消息conversation_id相同，落在同一分片，本地事务可以保证4个操作原子性
        } catch (Exception e) {
            log.error("写扩散双写失败, senderId={}, receiverId={}", senderId, receiverId, e);
            // 事务回滚，A也不会看到消息（保证一致性）
            // 发送失败ACK给A
            sendNack(senderId, chatMessage.getId(), "发送失败，请重试");
            return;
        }

        // 3. 路由投递
        String serverId = reactiveRedisTemplate.opsForValue()
            .get("im:route:" + receiverId).block();
        if (serverId != null && serverId.equals(getLocalServerId())) {
            // 同实例直接推送
            pushToUser(receiverId, chatMessage);
        } else if (serverId != null) {
            // 跨实例通过MQ路由
            rocketMQTemplate.convertAndSend("IM_ROUTE_TOPIC",
                new RouteMessage(receiverId, chatMessage, serverId));
        } else {
            // 离线：只存消息ID到离线队列，消息详情从DB查
            reactiveRedisTemplate.opsForList()
                .rightPush("im:offline:" + receiverId, String.valueOf(chatMessage.getId()))
                .block();
        }

        // 4. 更新未读计数（Hash结构：一个Key管所有会话）
        reactiveRedisTemplate.opsForHash()
            .increment("im:unread:" + receiverId, String.valueOf(senderId), 1)
            .block();

        // 5. 发送ACK给发送者
        sendAck(senderId, chatMessage.getId());
    }

    /**
     * 处理输入状态
     */
    private void handleTypingMessage(Long senderId, ImMessage message) {
        Long peerId = message.getPeerId();
        WebSocketSession peerSession = sessions.get(peerId);
        if (peerSession != null && peerSession.isOpen()) {
            String json = JSON.toJSONString(Map.of(
                "ver", 1,
                "type", "TYPING",
                "peerId", senderId,
                "isTyping", true
            ));
            peerSession.textMessage(json);
        }
    }

    /**
     * 生成会话ID：min(A,B) << 32 | max(A,B)
     * 保证确定性：无论A发消息还是B发消息，conversation_id相同
     */
    private Long generateConversationId(Long userIdA, Long userIdB) {
        long min = Math.min(userIdA, userIdB);
        long max = Math.max(userIdA, userIdB);
        return (min << 32) | max;
    }

    /**
     * 推送消息给本实例在线用户
     */
    private void pushToUser(Long userId, ChatMessage message) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            String json = JSON.toJSONString(Map.of(
                "ver", 1,
                "type", "CHAT",
                "msgId", message.getId(),
                "from", message.getSenderId(),
                "content", message.getContent(),
                "msgType", message.getMsgType(),
                "timestamp", System.currentTimeMillis()
            ));
            session.textMessage(json);
        }
    }

    /**
     * 注册/注销在线路由（使用ReactiveRedisTemplate，不阻塞EventLoop）
     */
    private void registerRoute(Long userId) {
        reactiveRedisTemplate.opsForValue()
            .set("im:route:" + userId, getLocalServerId(), Duration.ofSeconds(30))
            .block();
        reactiveRedisTemplate.opsForValue()
            .set("im:online:" + userId, "1", Duration.ofSeconds(30))
            .block();
    }

    private void unregisterRoute(Long userId) {
        reactiveRedisTemplate.delete("im:route:" + userId).block();
        reactiveRedisTemplate.delete("im:online:" + userId).block();
    }

    /**
     * 心跳Flux：每25秒发送PONG
     */
    private Flux<WebSocketMessage> getHeartbeatFlux(WebSocketSession session) {
        return Flux.interval(Duration.ofSeconds(25))
            .map(i -> session.textMessage("{\"type\":\"PONG\"}"));
    }

    private Long extractUserId(WebSocketSession session) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null) return null;
        String ticket = Arrays.stream(query.split("&"))
            .filter(s -> s.startsWith("ticket="))
            .findFirst()
            .map(s -> s.substring(7))
            .orElse(null);
        // ticket验证 → userId（ticket从SSE同款两步法获取）
        return ticket != null ? TicketUtil.parseUserId(ticket) : null;
    }

    private void sendAck(Long userId, Long msgId) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            session.textMessage(JSON.toJSONString(Map.of(
                "ver", 1, "type", "ACK", "msgId", msgId, "timestamp", System.currentTimeMillis()
            )));
        }
    }

    private void sendNack(Long userId, Long msgId, String reason) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            session.textMessage(JSON.toJSONString(Map.of(
                "ver", 1, "type", "NACK", "msgId", msgId, "reason", reason
            )));
        }
    }
}
```

### 6.2 跨实例消息路由

```java
// 说明：IM服务多实例部署时，跨实例消息通过RocketMQ路由
// 关键点：MQ Consumer过滤只消费目标为本实例的消息
@Component
@RocketMQMessageListener(
    topic = "IM_ROUTE_TOPIC",
    consumerGroup = "im-route-consumer-${server.port}"
)
@Slf4j
public class ImRouteConsumer implements RocketMQListener<MessageExt> {

    @Autowired
    private ImWebSocketHandler webSocketHandler;

    @Override
    public void onMessage(MessageExt messageExt) {
        RouteMessage routeMessage = JSON.parseObject(
            new String(messageExt.getBody(), StandardCharsets.UTF_8),
            RouteMessage.class);

        // 只处理目标为本实例的消息
        if (routeMessage.getTargetServerId().equals(getLocalServerId())) {
            webSocketHandler.pushToUser(
                routeMessage.getReceiverId(),
                routeMessage.getChatMessage());
            log.info("跨实例消息路由成功, receiverId={}", routeMessage.getReceiverId());
        }
    }
}
```

### 6.3 会话管理

```java
// 说明：维护用户会话列表，包含最后一条消息和未读数
// 关键点：双向会话（A和B各有一条记录）；未读计数Redis+DB双写
@Service
@Slf4j
public class ConversationService {

    @Autowired
    private ChatUserRelationMapper relationMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 更新会话（发送消息后调用）
     * 双方各有一条会话记录：A→B 和 B→A
     */
    @Transactional
    public void updateConversation(Long senderId, Long receiverId, String content, Integer msgType) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 更新发送者会话（senderId的视角）
        ChatUserRelation senderRelation = relationMapper
            .selectByUserAndPeer(senderId, receiverId);
        if (senderRelation == null) {
            senderRelation = new ChatUserRelation()
                .setUserId(senderId).setPeerId(receiverId)
                .setLastContent(content).setLastMsgType(msgType)
                .setUnreadCount(0).setIsDeleted((byte) 0);
            relationMapper.insert(senderRelation);
        } else {
            senderRelation.setLastContent(content)
                .setLastMsgType(msgType)
                .setUpdatedAt(now);
            if (senderRelation.getIsDeleted() == 1) {
                senderRelation.setIsDeleted((byte) 0); // 重新激活已删除的会话
            }
            relationMapper.updateById(senderRelation);
        }

        // 2. 更新接收者会话（receiverId的视角）+ 未读计数+1
        ChatUserRelation receiverRelation = relationMapper
            .selectByUserAndPeer(receiverId, senderId);
        if (receiverRelation == null) {
            receiverRelation = new ChatUserRelation()
                .setUserId(receiverId).setPeerId(senderId)
                .setLastContent(content).setLastMsgType(msgType)
                .setUnreadCount(1).setIsDeleted((byte) 0);
            relationMapper.insert(receiverRelation);
        } else {
            receiverRelation.setLastContent(content)
                .setLastMsgType(msgType)
                .setUnreadCount(receiverRelation.getUnreadCount() + 1)
                .setUpdatedAt(now);
            relationMapper.updateById(receiverRelation);
        }
    }

    /**
     * 获取会话列表
     */
    public PageResult<ConversationVO> getConversationList(Long userId, int page, int size) {
        // 查询未删除的会话，按更新时间倒序
        Page<ChatUserRelation> pageResult = relationMapper.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<ChatUserRelation>()
                .eq(ChatUserRelation::getUserId, userId)
                .eq(ChatUserRelation::getIsDeleted, 0)
                .orderByDesc(ChatUserRelation::getUpdatedAt));

        // 转换为VO，补充对方用户信息和实时未读数
        List<ConversationVO> voList = pageResult.getRecords().stream()
            .map(this::toConversationVO)
            .collect(Collectors.toList());

        return new PageResult<>(voList, pageResult.getTotal(), page, size);
    }

    /**
     * 标记与某人的消息全部已读
     */
    public void markAllRead(Long userId, Long peerId) {
        // 1. 更新DB未读数为0
        ChatUserRelation relation = relationMapper.selectByUserAndPeer(userId, peerId);
        if (relation != null) {
            relation.setUnreadCount(0);
            relationMapper.updateById(relation);
        }

        // 2. 更新Redis未读计数为0
        redisTemplate.opsForValue().set("im:unread:" + userId + ":" + peerId, "0");

        // 3. 批量更新消息已读状态
        // UPDATE t_chat_message SET is_read=1 WHERE receiver_id=? AND sender_id=? AND is_read=0
    }
}
```

### 6.4 离线消息处理

```java
// 说明：用户上线后推送离线消息，确认后从Redis删除
// 关键点：Redis List做离线队列（只存消息ID）；逐条ACK保证不丢消息
@Component
@Slf4j
public class OfflineMessageService {

    private static final String OFFLINE_KEY_PREFIX = "im:offline:";
    private static final int MAX_OFFLINE_MESSAGES = 1000;

    @Autowired
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    @Autowired
    private MessageService messageService;

    /**
     * 用户上线后推送离线消息
     * ⚠️ 关键变更：不再推送后直接delete！改为逐条ACK确认后删除
     * 原方案bug：推送过程中连接断了→消息已从Redis删除→消息永久丢失
     */
    public void pushOfflineMessages(Long userId, WebSocketSession session) {
        String key = OFFLINE_KEY_PREFIX + userId;
        Long count = reactiveRedisTemplate.opsForList().size(key).block();

        if (count == null || count == 0) {
            return;
        }

        log.info("推送离线消息, userId={}, count={}", userId, Math.min(count, MAX_OFFLINE_MESSAGES));

        // 批量获取离线消息ID（最多推1000条）
        List<String> msgIds = reactiveRedisTemplate.opsForList()
            .range(key, 0, MAX_OFFLINE_MESSAGES - 1).collectList().block();

        if (msgIds != null && !msgIds.isEmpty()) {
            // 根据消息ID从DB查询完整消息内容（Redis只存ID，不存完整JSON）
            List<ChatMessage> messages = messageService.getMessagesByIds(
                msgIds.stream().map(Long::valueOf).collect(Collectors.toList()));

            // 批量推送离线消息
            String batchMsg = JSON.toJSONString(Map.of(
                "ver", 1,
                "type", "OFFLINE",
                "msgs", messages,
                "total", messages.size()
            ));
            session.textMessage(batchMsg);

            // ⚠️ 不再直接delete！等待客户端逐条ACK确认
            // ACK处理逻辑在handleAckMessage中：收到ACK后从List中LREM对应的消息ID
            // 设置兜底超时：5分钟后如果还有未ACK的消息，说明连接已断开，保留在队列中
            // 下次重连时重新推送
        }
    }

    /**
     * ACK确认后从离线队列中删除消息
     */
    public void ackOfflineMessage(Long userId, Long msgId) {
        String key = OFFLINE_KEY_PREFIX + userId;
        // LREM: 从列表中删除指定值的1个元素
        reactiveRedisTemplate.opsForList().remove(key, 1, String.valueOf(msgId)).block();
        log.debug("离线消息ACK确认, userId={}, msgId={}", userId, msgId);
    }

    /**
     * 用户离线时暂存消息ID（不存完整消息）
     */
    public void storeOfflineMessage(Long userId, Long msgId) {
        String key = OFFLINE_KEY_PREFIX + userId;
        reactiveRedisTemplate.opsForList().rightPush(key, String.valueOf(msgId)).block();

        // 限制离线消息最大数量，防止Redis内存溢出
        Long size = reactiveRedisTemplate.opsForList().size(key).block();
        if (size != null && size > MAX_OFFLINE_MESSAGES) {
            reactiveRedisTemplate.opsForList().trim(key, -MAX_OFFLINE_MESSAGES, -1).block();
        }
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 WebSocket实现方案选型

**问题场景**：IM私信需要双向实时通信——买家发消息→商家实时收到→商家回复→买家实时收到。

**约束条件**：
1. **双向通信**：客户端和服务器都需要通过同一连接收发消息
2. **高并发**：10万+同时在线WebSocket连接
3. **低延迟**：消息端到端延迟<100ms
4. **与Spring生态集成**：项目已用Spring Boot，希望复用现有依赖
5. **开发效率**：3人团队，5天工期

**逐项分析**：

| 约束 | WebFlux+Netty | MVC+WebSocket | 原生Netty |
|------|---------------|---------------|-----------|
| 双向通信 | ✅ WebSocketHandler | ✅ 默认支持 | ✅ WebSocketServerHandshaker |
| 高并发10万+ | ✅ EventLoop非阻塞 | ❌ 每连接一线程 | ✅ EventLoop非阻塞 |
| 低延迟 | ✅ 非阻塞IO | ⚠️ 线程切换开销 | ✅ 零拷贝 |
| Spring集成 | ✅ 原生支持 | ✅ 原生支持 | ❌ 需手动集成 |
| 开发效率 | ✅ 中等 | ✅ 高 | ❌ 低（手动管理生命周期） |

**最终选择**：方案A（Spring WebFlux + Netty）

**放弃了什么**：原生Netty性能最高（100万+连接），但手动管理连接生命周期（握手、编解码、心跳、异常处理）复杂度太高。Spring WebFlux封装了这些细节，单机10万+连接完全满足IM场景（100万DAU × 10%在线 = 10万连接）。如果未来需要极致性能，可以剥离Spring框架升级为原生Netty。

> 💡 **反向思考**：Spring MVC + WebSocket为什么不行？因为Servlet模型是"每连接一线程"，10万连接=10万线程，上下文切换开销巨大，线程栈占用内存（每线程1MB → 100GB）。WebFlux基于Reactor模型，几个EventLoop线程即可处理10万连接。

### 7.2 消息存储方案选型

**问题场景**：用户A发私信给B，消息需要持久化，两人都需要能查到聊天记录。

**约束条件**：
1. **读性能**：查看聊天记录必须快（P99 < 50ms）
2. **写性能**：发消息不能因为写入慢而延迟推送
3. **删除/已读独立**：A删消息不影响B看到消息
4. **私信(2人)场景**：写扩散额外写入1份（可接受）；群聊(100人)写扩散额外写99份（不可接受）

**方案对比**：

| 方案 | 满足约束1 | 满足约束2 | 满足约束3 | 私信开销 | 群聊开销 |
|------|----------|----------|----------|---------|---------|
| 写扩散 | ✅ 只查自己的表 | ✅ 写完即推 | ✅ 各自独立 | +1份(可接受) | +N-1份(不可接受) |
| 读扩散 | ⚠️ 需实时聚合 | ✅ 只写1份 | ❌ 需额外状态表 | 无额外写入 | 无额外写入 |

**最终选择**：私信用写扩散

**放弃了什么**：写扩散下，A发消息给B需要写A的消息+B的消息两份。对于私信(2人)只多写1份，读取时无需聚合，性能更好。如果是群聊场景(100人)，写扩散要写100份，IO爆炸，那时应该切换为读扩散。

**取舍逻辑**：读取频率远高于写入频率（用户刷聊天记录10次才发1条消息），所以"读快"的优先级高于"写省"。写扩散牺牲写入换读取性能——这个trade-off在私信场景下是值得的。

### 7.3 消息ID方案选型

**问题场景**：IM消息需要全局唯一且趋势递增的ID，用于消息排序和去重。

**约束条件**：
1. **有序性**：消息必须按发送顺序排列（不能乱序）
2. **性能**：生成ID不能成为瓶颈（不能每次都访问DB/Redis）
3. **可靠性**：不能生成重复ID
4. **分布式**：多实例部署时ID仍然唯一且递增

**方案对比**：

| 方案 | 满足约束1 | 满足约束2 | 满足约束3 | 满足约束4 |
|------|----------|----------|----------|----------|
| 雪花算法 | ✅ 趋势递增(时间有序) | ✅ 本地生成 | ⚠️ 时钟回拨风险 | ✅ 天然支持 |
| DB自增 | ✅ 严格递增 | ❌ 依赖DB写入 | ✅ DB保证唯一 | ❌ 分库后不连续 |
| Redis INCR | ✅ 严格递增 | ⚠️ 依赖Redis | ✅ Redis保证唯一 | ⚠️ 需分段分配 |

**最终选择**：方案A（雪花算法）

**放弃了什么**：雪花算法是"趋势递增"而非"严格递增"——同一毫秒内不同workerId的ID可能不递增。但IM消息排序不需要严格递增，按时间排序即可，趋势递增足够。另外，时钟回拨是真实风险：服务器NTP同步时如果时钟回退，可能生成重复ID。防护措施：回拨<5ms时等待追回，≥5ms时拒绝生成+报警。

---

## 🐛 八、踩坑记录

### 8.1 WebSocket连接通过网关超时

- **现象**：WebSocket连接建立后60秒被网关断开
- **原因**：Spring Cloud Gateway默认超时60秒，WebSocket长连接需要配置无限超时
- **解决**：Gateway路由配置 `predicates: Path=/api/im/ws/**` + 自定义WebSocket代理过滤器
- **教训**：WebSocket通过网关代理需要特殊配置，不能当普通HTTP路由处理

### 8.2 消息时序错乱

- **现象**：前端显示的消息顺序与发送顺序不一致
- **原因**：多线程并发处理消息，先到的消息ID更大但处理更慢
- **解决**：前端按消息ID排序而非到达顺序；后端单线程写会话
- **教训**：消息的"发送顺序"和"到达顺序"是两回事，必须用ID排序

### 8.3 离线消息重复推送

- **现象**：用户上线后收到重复的离线消息
- **原因**：断线重连时先推送离线消息，再建立新连接又推送一次
- **解决**：推送离线消息前先删除Redis队列；或用消息ID去重
- **教训**：断线重连和首次上线要区分处理，避免重复推送

### 8.4 写扩散双写一致性问题

- **现象**：A发消息给B，A看到消息已发送，B收不到
- **原因**：写扩散双写（A的表+B的表）不在同一个事务中——写A成功但写B失败，导致数据不一致
- **解决**：
  1. **同分片保证本地事务**：A和B的消息使用同一个`conversation_id`做分片键，确保落在同一分片，可使用本地事务保证原子性
  2. **跨分片场景用最终一致性+补偿**：写A成功后发MQ消息，消费端写B失败则重试3次，仍失败则写补偿表，定时任务扫描补偿表重试
  3. **发送端感知失败**：双写全部成功后才发ACK给A；部分失败则发NACK让A重试
- **教训**：写扩散不是"写两次"这么简单，必须考虑部分失败的一致性问题。同分片用事务，跨分片用补偿+重试

### 8.5 消息风暴：热门笔记写入放大

- **现象**：百万粉博主发笔记，DB IO瞬间打满，IM服务CPU 100%
- **原因**：写扩散下，1条笔记的互动事件会写入N份消息（N=粉丝数）。100万粉丝=100万条INSERT——这是写入放大的根源
- **解决**：
  1. **写扩散不适合大群/热门场景**：对于1对1私信用写扩散，对于热门笔记的互动通知走通知系统的聚合逻辑（不写IM的消息表）
  2. **异步批量写入**：消息先写MQ，消费端批量INSERT（100条一批），降低DB IO压力
  3. **限流**：对同一笔记的互动通知做合并（5分钟窗口聚合），不重复写
- **教训**：写扩散的写入放大在热门场景下是致命的。系统设计必须区分私信（2人写扩散OK）和热门互动（走通知聚合）

### 8.6 WebSocket连接泄露

- **现象**：服务运行数日后，Netty EventLoop线程全部被占用，新连接无法建立
- **原因**：移动端网络切换时WebSocket连接进入"半开"状态——客户端已断开但服务端未感知（TCP连接未正常关闭），连接对象未释放
- **解决**：
  1. 心跳超时检测：30秒无PING响应则主动关闭连接
  2. 连接注册时绑定`onClose`/`onError`回调，确保异常时清理资源
  3. 监控告警：在线连接数 > 阈值（如DAU的20%）时告警
  4. 最大连接数限制：单实例最大10万WebSocket连接，超出拒绝并返回友好提示
- **教训**：长连接必须做**超时清理**和**资源回收**，否则是内存泄露的定时炸弹

### 8.7 Netty直接内存泄漏

- **现象**：运行几小时后`java.lang.OutOfMemoryError: Direct buffer memory`
- **原因**：WebSocket消息的ByteBuf在Netty的EventLoop中创建后，未正确释放（引用计数未归零）
- **解决**：
  1. 使用`ReferenceCountUtil.release(msg)`释放消息
  2. 使用`SimpleChannelInboundHandler`（自动释放）代替`ChannelInboundHandlerAdapter`
  3. 配置`-XX:MaxDirectMemorySize=512m`限制直接内存上限
  4. 监控直接内存使用量（JMX: `java.nio:type=BufferPool,name=direct`）
- **教训**：Netty的引用计数机制需要开发者主动配合，忘记release就会导致直接内存泄漏

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 实际结果 | 通过 |
|----------|------|----------|----------|------|
| WebSocket连接（两步法） | POST /ws/ticket → ws://host/ws?ticket=xxx | 连接成功 | | ⬜ |
| 发送文本消息 | {ver:1,type:"CHAT",to:1002,content:"你好"} | 对方实时收到 | | ⬜ |
| 发送图片消息 | {ver:1,type:"CHAT",to:1002,msgType:1} | 对方收到图片URL | | ⬜ |
| 未读计数 | A发3条消息给B | B的未读数=3 | | ⬜ |
| 标记已读 | {ver:1,type:"READ",peerId:1002,msgId:456} | 未读数清零 | | ⬜ |
| 输入状态 | {ver:1,type:"TYPING",peerId:1002} | 对方看到"正在输入" | | ⬜ |
| 会话列表 | GET /api/im/conversations | 按时间倒序返回 | | ⬜ |
| 离线消息 | B离线→A发3条→B上线 | B收到3条离线消息 | | ⬜ |
| 心跳保活 | 30秒发一次PING | 收到PONG | | ⬜ |
| 断线重连 | 网络断开后恢复 | 自动重连+推送离线消息 | | ⬜ |

### 9.2 压测数据

> ⚠️ 以下为待压测数据占位，标注"待压测"而非编造数字

| 场景 | 并发数 | QPS | 平均RT | P99 RT | 错误率 |
|------|--------|-----|--------|--------|--------|
| WebSocket连接建立 | - | - | - | - | - |
| 消息发送 | - | - | - | - | - |
| 历史消息查询 | - | - | - | - | - |
| 会话列表查询 | - | - | - | - | - |

**压测方法论**：
- **工具**：wrk（HTTP接口）+ 自研WebSocket压测脚本（JMeter WebSocket插件备选）
- **环境**：单机4核8G（模拟生产最小配置）；集群4节点16核32G（模拟生产部署）
- **关注瓶颈**：WebSocket连接数（受限于文件描述符和Netty EventLoop线程）；Redis INCR/DECR QPS；MQ消费速率；Netty直接内存使用量
- **优化对比**：优化前→定位瓶颈→优化→优化后→对比提升幅度

### 9.3 关键场景验证

- [ ] 消息不丢失：发送1000条消息，统计接收条数=1000
- [ ] 消息不重复：同一条消息不会收到2次
- [ ] 消息有序：前端按msgId排序后与发送顺序一致
- [ ] 离线消息完整性：离线1000条消息后上线，全部收到
- [ ] 并发发送安全：10个线程同时向同一用户发消息，无数据错乱
- [ ] WebSocket断线重连：手动断网后恢复，验证重连成功+离线消息推送
- [ ] 心跳超时清理：模拟客户端无响应90秒，验证服务端主动关闭连接
- [ ] 写扩散一致性：模拟写B失败，验证A收到NACK并重试
- [ ] 消息风暴：模拟1000条消息瞬时投递，验证消费降级策略

---

## 🎤 十、面试考点

### Q1: IM为什么用WebSocket而不是SSE？

**实战式回答**：

> "我们当时先用了WebSocket，发现通知场景不需要双向通信，而且WebSocket的协议升级在某些企业代理服务器下被拦截（我们实测某企业代理下WebSocket连接失败率8%，SSE为0%）。所以通知系统切换到了SSE。但IM场景天然需要双向通信——用户发消息+接收消息必须在同一条通道完成，所以IM保留WebSocket。两个系统各用最合适的技术。"

### Q2: 消息存储用写扩散还是读扩散？

**实战式回答**：

> "我们私信用的写扩散，群聊以后会切换到读扩散。选写扩散做私信是因为：1对1场景写扩散只多写1份（2份总共），读取时无需聚合，查询性能最好。但写扩散在群聊场景写入放大太严重——100人群聊=100份写入，DB IO爆炸。微信也是单聊用写扩散、群聊用读扩散。我们的取舍逻辑是：读取频率远高于写入（用户刷10次聊天记录才发1条消息），所以'读快'的优先级高于'写省'。"

### Q3: 离线消息怎么处理？

**实战式回答**：

> "我们Redis只存消息ID不存完整JSON，节省内存。用户在线时消息直接WebSocket推送；离线时消息ID写入Redis List做离线队列，同时消息内容持久化到MySQL。上线后从Redis批量拉取消息ID，再从MySQL查详情推送。推送后不直接删除，而是等客户端逐条ACK确认后再LREM——这样即使推送过程中连接断了，消息也不会丢。离线队列限制1000条，超出部分用户上线后从MySQL历史记录拉取。"

### Q4: 如何保证消息不丢失？

**实战式回答**：

> "三层保障：1）发送端：消息先写DB再推送，写入失败则重试不ACK；2）接收端：每条消息需要ACK确认，未确认的消息下次上线重推；3）离线消息：Redis暂存+MySQL持久化双保险，即使Redis故障消息也不丢——客户端可随时从MySQL拉取历史消息补齐。实际运行中我们还没丢过消息，但为了兜底，做了定时对账：每5分钟比对Redis未读计数和MySQL COUNT，差异以DB为准修正Redis。"

### Q5: 写扩散双写一致性怎么保证？

**实战式回答**：

> "写扩散双写的核心风险是：写A的消息成功但写B的消息失败，A看到消息已发送但B收不到。我们的方案是：因为A和B的消息使用同一个conversation_id做分片键，双写的两张表在同一分片，所以可以用本地事务保证原子性。如果未来需要跨分片（不同用户在不同分片），我们准备了最终一致性+补偿方案：写A成功后发MQ消息，消费端写B失败重试3次，仍失败写补偿表，定时任务扫描补偿表重试。双写全部成功才给A发ACK，部分失败发NACK让A重试。"

### Q6: 雪花算法时钟回拨怎么办？

**实战式回答**：

> "时钟回拨是真实风险，我们做了两档防护：回拨小于5ms时线程休眠等待时钟追回；回拨大于等于5ms时直接拒绝生成ID并报警（说明这台机器的NTP同步出了严重问题，继续生成可能产生重复ID）。报警后运维介入检查NTP配置。实际运行中我们遇到过一次2ms的回拨，休眠后正常生成，没有产生重复ID。另外我们在消息表上加了唯一索引做兜底——即使极端情况生成了重复ID，INSERT也会失败，不会写入脏数据。"


---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《Netty实战》 | 第6章 | WebSocket协议实现 |
| 📖 《微信技术架构》 | 消息系统 | 写扩散vs读扩散、消息可靠性 |
| 📄 00-technical-specification-outline.md | §2.12 | IM数据库表设计 |
| 📄 野火IM开源项目 | 架构设计 | 连接管理、消息路由参考 |
