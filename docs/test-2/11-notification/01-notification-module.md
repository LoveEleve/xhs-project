# 11-notification 通知服务 — 架构文档

> 端口：19013 | 数据库：my_xhs_notification | 更新时间：2026-07-29

---

## 1. 模块定位

通知服务是 my-xhs 平台的**实时消息推送中枢**，负责接收来自社交/内容/订单等服务的业务事件（通过 RocketMQ），生成通知并实时推送给在线用户。

核心能力：
- **SSE 长连接**：两步法 Ticket 认证，跨实例推送（Redis Pub/Sub）
- **通知聚合**：5 分钟窗口内合并同类通知，减少 DB 写入
- **未读计数**：Redis 原子计数 + Lua 防负数 + XXL-Job 定时对账
- **模板化渲染**：DB 驱动的推送模板（标题/内容/聚合标题）

---

## 2. 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                     外部服务（social/content/order）               │
│                            │                                     │
│                    RocketMQ NOTIFICATION_TOPIC                    │
│                            │                                     │
│              ┌─────────────▼──────────────┐                      │
│              │  NotificationEventConsumer  │                      │
│              │  - msgId 幂等（Redis 24h）   │                      │
│              │  - traceId 跨 MQ 传播       │                      │
│              │  - 跳过自己给自己的通知      │                      │
│              └─────────────┬──────────────┘                      │
│                            │                                     │
│              ┌─────────────▼──────────────┐                      │
│              │    NotificationService      │                      │
│              │  processEvent(event)        │                      │
│              │  ① buildNotification       │                      │
│              │  ② aggregate               │                      │
│              │  ③ update unread           │                      │
│              │  ④ SSE push                │                      │
│              └─────────────┬──────────────┘                      │
│                            │                                     │
│        ┌───────────────────┼───────────────────┐                 │
│        ▼                   ▼                    ▼                 │
│ ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐       │
│ │ Notif.Aggregator│ │ UnreadCount │  │ SseEmitterManager│       │
│ │  Redis SETNX   │ │ Redis INCR  │  │ ConcurrentHashMap│       │
│ │  5min 窗口     │ │ Lua DECR   │  │ Redis Pub/Sub   │       │
│ └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘       │
│        │                 │                    │                  │
│   ┌────▼────┐      ┌────▼────┐      ┌────────▼─────────┐       │
│   │ MySQL   │      │ Redis   │      │ SseCrossInstance │       │
│   │ t_notif │      │ notify: │      │ Subscriber       │       │
│   │ (主从)   │      │ unread:*│      │ (跨实例推送)      │       │
│   └─────────┘      └─────────┘      └──────────────────┘       │
│                                                                  │
│    前端（浏览器 EventSource） ←──── SSE (text/event-stream)       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 源码清单

| 文件 | 职责 |
|---|---|
| `NotificationApplication.java` | 启动类，@EnableScheduling + @EnableAsync |
| `controller/NotificationController.java` | REST API（SSE、列表、已读、未读计数） |
| `controller/NotificationTestController.java` | 测试接口（仅 dev profile，绕过 MQ 直接发事件） |
| `service/NotificationService.java` | 核心业务编排（processEvent + 查询 + 标记已读） |
| `service/NotificationAggregator.java` | 5 分钟窗口聚合（Redis SETNX Lua + MySQL 更新） |
| `service/SseTicketService.java` | SSE Ticket 生成/验证（30 秒一次性） |
| `service/UnreadCountService.java` | 未读计数（Redis INCR/DECR + Lua 防负数） |
| `sse/SseEmitterManager.java` | SSE 连接管理（心跳/推送/跨实例路由） |
| `sse/SseCrossInstanceSubscriber.java` | Redis Pub/Sub 消费者（跨实例推送） |
| `consumer/NotificationEventConsumer.java` | RocketMQ 消费者（幂等 + traceId 恢复） |
| `job/UnreadReconcileJob.java` | XXL-Job 定时对账（每 5 分钟） |
| `entity/Notification.java` | 通知实体（MyBatis-Plus + 逻辑删除） |
| `entity/PushTemplate.java` | 推送模板实体 |
| `dto/NotificationEventDTO.java` | MQ 消息体 DTO |
| `dto/NotificationVO.java` | 通知列表 VO |
| `dto/UnreadCountVO.java` | 未读计数 VO |
| `dto/NotificationType.java` | 通知类型枚举（5 种） |
| `mapper/NotificationMapper.java` | MyBatis-Plus Mapper + 自定义 SQL |
| `mapper/PushTemplateMapper.java` | 模板 Mapper |

---

## 4. 数据模型

### 4.1 t_notification（通知表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花算法 ID |
| user_id | BIGINT | 接收用户 ID |
| type | TINYINT | 1-点赞 2-评论 3-关注 4-系统 5-订单 |
| title | VARCHAR(128) | 通知标题（模板渲染后） |
| content | VARCHAR(512) | 通知内容 |
| sender_id | BIGINT | 发送者 ID |
| sender_name | VARCHAR(32) | 发送者昵称（冗余） |
| sender_avatar | VARCHAR(512) | 发送者头像 URL（冗余） |
| target_id | BIGINT | 关联目标 ID |
| target_type | TINYINT | 目标类型：1-笔记 2-商品 3-订单 |
| is_read | TINYINT | 0-未读 1-已读 |
| is_aggregated | TINYINT | 0-否 1-是（被聚合的通知） |
| aggregate_id | BIGINT | 聚合目标通知 ID |
| aggregate_count | INT | 聚合数量 |
| notify_date | DATE | 通知日期（VIRTUAL：DATE(created_at)） |
| extra_data | TEXT | 扩展数据 JSON |
| deleted | TINYINT | 逻辑删除（MyBatis-Plus @TableLogic） |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

索引：
- `idx_user_id_created (user_id, created_at DESC)` — 列表查询
- `idx_user_type_read (user_id, type, is_read)` — 已读筛选
- `UNIQUE uk_aggregate (user_id, type, target_id, notify_date)` — 聚合唯一约束

### 4.2 t_push_template（推送模板表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 自增 ID |
| type | VARCHAR(32) | LIKE/COMMENT/FOLLOW/SYSTEM/ORDER |
| title_template | VARCHAR(128) | 标题模板（占位符：{sender}, {target}, {content}） |
| content_template | VARCHAR(512) | 内容模板 |
| aggregate_title_template | VARCHAR(128) | 聚合标题模板（{sender}, {count}） |
| status | TINYINT | 0-禁用 1-启用 |

预置数据（test-data-init.sql）：
- like: `点赞通知` / `{sender} 赞了你的笔记`
- comment: `评论通知` / `{sender} 评论了你的笔记`
- follow: `关注通知` / `{sender} 关注了你`
- system: `系统通知` / `{content}`
- order: `订单通知` / `订单 {orderNo} 状态更新为 {status}`

### 4.3 Redis Key 设计

| Key 模式 | 类型 | TTL | 说明 |
|---|---|---|---|
| `notify:sse:{userId}` | String (serverId) | 30s 心跳续期 | SSE 在线路由 |
| `notify:sse:ticket:{ticket}` | String (userId) | 30s | SSE Ticket |
| `notify:sse:channel` | Pub/Sub Channel | — | 跨实例推送 |
| `notify:agg:{userId}:{type}:{targetId}` | String (mainId) | 5min | 聚合窗口锁 |
| `notify:unread:{userId}` | String (total) | 永久 | 总未读数 |
| `notify:unread:type:{userId}` | Hash (type→count) | 永久 | 分类未读数 |

---

## 5. API 端点

### 5.1 SSE 连接

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/notification/sse/ticket` | 获取 SSE Ticket（需 `X-User-Id` Header） |
| GET | `/api/notification/sse` | 建立 SSE 连接（`?ticket=xxx`，返回 text/event-stream） |

### 5.2 通知查询与已读

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notification/list` | 通知列表（分页 + 类型筛选） |
| GET | `/api/notification/unread-count` | 未读计数（总 + 分类） |
| POST | `/api/notification/read/{id}` | 标记单条已读 |
| POST | `/api/notification/read-by-type/{type}` | 按类型全部已读 |
| POST | `/api/notification/read-all` | 全部标记已读 |

### 5.3 调试

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notification/sse/online-count` | SSE 在线连接数 |
| POST | `/api/notification/test/send` | 模拟通知事件（仅 dev 环境） |

---

## 6. 核心流程

### 6.1 通知生成流程（processEvent）

```
1. buildNotification(event)
   └─ 查 PushTemplate → 模板渲染 title/content
2. aggregator.processWithAggregate(notification)
   ├─ Redis Lua SETNX 窗口锁（先用 "PENDING" 占位）
   │  ├─ 第一条 → INSERT DB → 用真实 ID 替换 "PENDING"
   │  └─ 后续 → 不写 DB，直接更新主通知 aggregate_count/title
3. unreadCountService.incrementUnread(userId, type) — 仅新建通知时
   ├─ Redis INCR total
   └─ Redis HINCRBY type
4. SSE 推送（仅在线用户）
   ├─ pushNotification → SSE event: notification
   └─ pushUnreadCount → SSE event: unread-count
```

> **修复 (2026-07-29)**：重构为 SETNX 先于 INSERT 模式。原实现 INSERT 先于 SETNX，与 `uk_aggregate(user_id,type,target_id,notify_date)` 唯一约束冲突——同一天第二次通知 INSERT 会触发 `DuplicateKeyException`。修复：(1) 降低唯一约束为普通索引 (2) SETNX 先用 `"PENDING"` 占位，确认窗口后再 INSERT，避免无效的 INSERT→DELETE 循环。

### 6.2 聚合机制详解

**聚合 Key**：`userId:type:targetId`（同一用户 + 同一类型 + 同一目标）

**原子性**：Lua 脚本保证 SETNX + 写入通知 ID 原子操作

**流程图**：
```
新通知到达
  │
  ├─ 1. Redis Lua: SETNX(aggregateKey, "PENDING", TTL=5min)
  │     │
  │     ├─ 返回 "1" → 窗口内第一条
  │     │     ├─ INSERT DB（获取 ID）
  │     │     └─ SET aggregateKey = 真实 ID
  │     │
  │     └─ 返回 mainId / "PENDING" → 窗口内后续通知
  │           ├─ 不写 DB（跳过 INSERT）
  │           ├─ UPDATE incrementAggregateCount(mainId)
  │           ├─ UPDATE aggregateTitle(mainId)
  │           └─ 返回主通知对象（用于 SSE 推送）
```

**设计决策**：
- 被聚合的通知**逻辑删除**而非物理删除（MyBatis-Plus @TableLogic → `deleted=1`），避免主键冲突
- 聚合窗口内不增加未读计数（用户已看到红点）
- 聚合标题格式：`{sender}等{count}人{action}` 或模板自定义

### 6.3 SSE 连接流程

#### 两步法认证
```
前端                       notification-server              Redis
 │                              │                             │
 │  POST /sse/ticket            │                             │
 │  Header: X-User-Id: 123      │                             │
 │─────────────────────────────>│                             │
 │                              │ SET notify:sse:ticket:{uuid} = "123" EX 30
 │                              │─────────────────────────────>
 │  {"ticket":"abc123",         │                             │
 │   "expiresIn":30}            │                             │
 │<─────────────────────────────│                             │
 │                              │                             │
 │  GET /sse?ticket=abc123      │                             │
 │─────────────────────────────>│                             │
 │                              │ GETANDDELETE ticket → "123" │
 │                              │─────────────────────────────>
 │                              │ user=123, create SseEmitter │
 │                              │ SET notify:sse:123 = serverId EX 30
 │                              │─────────────────────────────>
 │  event: connected            │                             │
 │<─────────────────────────────│                             │
 │  event: heartbeat (每10s)     │                             │
 │<─────────────────────────────│  Pipeline SET 续期（每10s）  │
```

**为什么需要 Ticket？**
- 浏览器 EventSource API 不支持自定义 Header，Token 放 URL 中会泄漏到浏览器历史、Nginx 日志、CDN 日志
- 两步法：Token 通过 HTTP POST Header 传输 → 换取 30 秒有效一次性 Ticket → Ticket 用于 SSE 连接
- `getAndDelete` 保证 Ticket 一次性使用，即使泄漏也无法重用

#### 跨实例推送
```
实例 A（serverId=10.0.0.1:19013）    实例 B（serverId=10.0.0.2:19013）
         │                                    │
 user=123 在线                             user=456 在线
         │                                    │
   pushNotification(456)                       │
         │                                    │
   本实例无 456                                │
   查 Redis: notify:sse:456 = "10.0.0.2:19013"
         │                                    │
   PUBLISH notify:sse:channel                  │
   {"userId":456,"event":"notification",      │
    "data":{...}}                             │
         │                                    │
         └──────────── Redis ─────────────────>│
                                        onMessage → userId=456 在本实例
                                        → pushToLocalUser(456, "notification", data)
```

### 6.4 未读计数机制

```
incrementUnread(userId, type)        decrementUnread(userId, type)
    ├─ INCR notify:unread:{uid}          ├─ Lua DECR 防负数
    └─ HINCRBY notify:unread:type:       └─ Lua HDECR 防负数
       {uid} {type} 1
```

**Lua 防负数**：
```lua
-- SAFE_DECR: 读取 → 判断 ≤0 → 返回 0 否则 DECR
local count = redis.call('GET', KEYS[1])
if count == false or tonumber(count) <= 0 then return 0 end
return redis.call('DECR', KEYS[1])
```

**按类型重置（Lua 原子操作）**：
```lua
-- RESET_BY_TYPE: 读取类型计数 → 减去总未读 → 归零类型计数
local typeCount = redis.call('HGET', KEYS[2], ARGV[1])
local total = redis.call('GET', KEYS[1])
local newTotal = tonumber(total) - tonumber(typeCount)
redis.call('SET', KEYS[1], tostring(newTotal))
redis.call('HSET', KEYS[2], ARGV[1], '0')
```

### 6.5 对账机制（UnreadReconcileJob）

- **调度**：XXL-Job，Cron = `0 0/5 * * * ?`（每 5 分钟）
- **逻辑**：
  1. 游标分页扫描 MySQL 未读通知（`is_read=0`，500 条/批）
  2. 按 userId 聚合统计每种 type 的数量
  3. 与 Redis 未读计数对比
  4. 不一致时用 `forceSetUnread` 以 DB 为准修复
- **限速**：批次间 `Thread.sleep(50ms)`，避免 Redis 重启后瞬间打满
- **退出条件**：`batch.size() < queryLimit`（SQL LIMIT = 5000）

---

## 7. 幂等设计

两级幂等保障：

1. **MQ msgId 去重（24h Redis）**：`MessageIdempotentHelper.isFirstProcess("notify:consumed", msgId, 86400)`
2. **聚合窗口锁（Redis SETNX）**：同一 userId+type+targetId 在 5 分钟内只创建一条主通知

注意：通知表本身没有业务唯一键（同一用户可能收到多条同类型通知），因此不依赖 DB 唯一约束做幂等。

---

## 8. 配置要点

| 配置项 | 值 | 说明 |
|---|---|---|
| 端口 | 19013 | server.port |
| Tomcat 线程池 | max=150, min=15 | |
| MySQL 主库 | 21.130.247.89:13306 | my_xhs_notification |
| MySQL 从库 | 21.130.247.89:13310 | 读写分离 |
| Redis Sentinel | 26379/26380/26381 | master=mymaster |
| Redis Cache | 16380 | allkeys-lru |
| Redis Business | 16381 | noeviction |
| RocketMQ NS | 9876;9877 | |
| Nacos | 18848 | namespace=my-xhs |
| XXL-Job | 18080 | appname=my-xhs-notification, executor=9990 |
| Logstash | 15044 | JSON 格式推送 |

---

## 9. 依赖关系

### 上游依赖
- **RocketMQ NOTIFICATION_TOPIC**：social/content/order 服务发送通知事件
- **MySQL**：通知表读写
- **Cache Redis (16380)**：幂等标记
- **Business Redis (16381)**：SSE 路由、Ticket、聚合窗口、未读计数
- **Nacos**：服务注册与配置
- **XXL-Job**：对账调度

### 下游消费者
- **前端浏览器**：EventSource 连接 SSE endpoint

### 不依赖其他微服务
notification 服务不调用其他微服务的 Feign 接口（无 Outbound Feign），只通过 MQ 消费事件。

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| SSE vs WebSocket | SSE | 单向推送场景，HTTP 天然穿透代理/防火墙，浏览器 EventSource 原生支持 |
| Ticket 两步认证 | Redis getAndDelete | EventSource 不支持自定义 Header，避免 Token URL 泄漏 |
| 跨实例推送 | Redis Pub/Sub | 即发即忘，延迟低（毫秒级），无需持久化 |
| 聚合方式 | 存储层聚合（被聚合逻辑删除） | 减少 DB 写入；不增加未读计数 |
| 聚合窗口 | 5 分钟 Redis SETNX | 用户感知合理；Lua 原子操作 |
| 未读计数 | Redis + DB 对账 | Redis 高性能；XXL-Job 保证最终一致 |
| 幂等 | msgId + 聚合窗口 | 通知表无天然唯一键 |

---

## 11. 通知类型

| Code | Name | 触发场景 | 默认动作文案 |
|:--:|---|---|---|
| 1 | LIKE | 用户点赞笔记 | 赞了你的笔记 |
| 2 | COMMENT | 用户评论笔记 | 评论了你的笔记 |
| 3 | FOLLOW | 用户关注 | 关注了你 |
| 4 | SYSTEM | 系统通知 | 发送了系统通知 |
| 5 | ORDER | 订单状态变更 | 更新了订单状态 |

---

## 12. 与 HOMEBFF 的关系

notification 是独立服务，前端直接连接 SSE（不经 BFF 代理）。home BFF（19015）仅通过 `/api/notification/list` 和 `/api/notification/unread-count` 获取通知数据，不参与 SSE 连接管理。
