# my-xhs-analytics 数据分析模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-analytics/` |
| 端口 | 19003 |
| 服务名 | `my-xhs-analytics`（Nacos） |
| 数据库 | `my_xhs_analytics`（MySQL 13306 主 / 13310 从，读写分离） |
| Java 源文件 | 27 个 |
| Lua 脚本 | 7 个 |
| 启动类 | `AnalyticsApplication.java` |

**职责边界**：社交互动——关注/取关、点赞/取消点赞、收藏/取消收藏。**Redis 是权威数据源，MySQL 是异步持久化备份**（Follow 除外，使用同步双写）。

---

## 1. 数据模型

### 1.1 数据库表（my_xhs_analytics 库）

**`t_follow` — 关注关系表**

```sql
id              BIGINT PRIMARY KEY        -- 雪花 ID
user_id         BIGINT NOT NULL           -- 关注者
follow_user_id  BIGINT NOT NULL           -- 被关注者
created_at      DATETIME DEFAULT NOW()

UNIQUE INDEX uk_user_follow (user_id, follow_user_id)  -- 幂等保证
```

**`t_like` — 点赞表**

```sql
id         BIGINT PRIMARY KEY
user_id    BIGINT NOT NULL
biz_type   TINYINT NOT NULL              -- 1=笔记 2=评论
biz_id     BIGINT NOT NULL
created_at DATETIME DEFAULT NOW()

UNIQUE INDEX uk_user_biz (user_id, biz_type, biz_id)  -- 幂等保证
```

**`t_favorite` — 收藏表**

```sql
id         BIGINT PRIMARY KEY
user_id    BIGINT NOT NULL
note_id    BIGINT NOT NULL
created_at DATETIME DEFAULT NOW()

UNIQUE INDEX uk_user_note (user_id, note_id)  -- 幂等保证
```

### 1.2 数据分层策略

```
┌─────────────────────────────────────────────┐
│  Redis（实时读写，权威数据源）                  │
│  Set / ZSet / String counter                 │
├─────────────────────────────────────────────┤
│  MQ SOCIAL_TOPIC（异步落库）                   │
│  LIKE / UNLIKE / FAVORITE / UNFAVORITE      │
├─────────────────────────────────────────────┤
│  MySQL（持久化备份 + 对账修复基准）             │
│  t_follow / t_like / t_favorite               │
└─────────────────────────────────────────────┘
```

| 业务 | L1 Redis 权威 | L2 持久化路径 | L3 对账 |
|:--:|------|------|:--:|
| **Like** | Set SADD/SREM + 反向索引 | syncSend SOCIAL_TOPIC → Consumer INSERT/DELETE | ❌ |
| **Follow** | ZSet ZADD/ZREM + counter INCR/DECR | 独立 Redis Lua + MySQL INSERT（无 @Transactional，自动提交） | ✅ FollowCounterRepairJob |
| **Favorite** | ZSet ZADD/ZREM | asyncSend SOCIAL_TOPIC → Consumer INSERT/DELETE | ❌ |

---

## 2. 接口清单（16 个 REST 端点）

### 2.1 关注接口（7 个）— `/api/social`

| 方法 | 路径 | 鉴权 | 参数 | 返回 |
|------|------|:--:|------|------|
| `POST` | `/api/social/follow/{targetUserId}` | X-User-Id | 路径参数 | `R<Void>` |
| `DELETE` | `/api/social/follow/{targetUserId}` | X-User-Id | 路径参数 | `R<Void>` |
| `GET` | `/api/social/following/{userId}` | 公开 | page, size | `R<List<FollowVO>>` |
| `GET` | `/api/social/follower/{userId}` | 公开 | page, size | `R<List<FollowVO>>` |
| `GET` | `/api/social/common/{targetUserId}` | X-User-Id | 路径参数 | `R<List<Long>>` |
| `GET` | `/api/social/relation/{targetUserId}` | X-User-Id | 路径参数 | `R<{isFollowing, isFollowBack, isMutual}>` |
| `POST` | `/api/social/internal/repair-counter/{userId}` | 内部 | 路径参数 | `R<Void>` |

### 2.2 点赞接口（5 个）— `/api/social/like`

| 方法 | 路径 | 鉴权 | 限流/幂等 | 参数 | 返回 |
|------|------|:--:|------|------|------|
| `POST` | `/api/social/like` | X-User-Id | @RateLimit(60s/30) + @Idempotent(5s) | `LikeRequest {bizType, bizId}` | `R<{liked:true}>` |
| `DELETE` | `/api/social/like` | X-User-Id | 无（不对称：缺少 @RateLimit/@Idempotent） | `LikeRequest {bizType, bizId}` | `R<{unliked:true}>` |
| `GET` | `/api/social/like/status` | X-User-Id | — | `bizType, bizId` | `R<{liked:boolean}>` |
| `GET` | `/api/social/like/batch-status` | X-User-Id | — | `bizType, bizIds` (逗号分隔) | `R<Map<Long,Boolean>>` |
| `GET` | `/api/social/like/count` | 公开 | — | `bizType, bizId` | `R<{count}>` |

### 2.3 收藏接口（4 个）— `/api/social/favorite`

| 方法 | 路径 | 鉴权 | 限流/幂等 | 参数 | 返回 |
|------|------|:--:|------|------|------|
| `POST` | `/api/social/favorite` | X-User-Id | @RateLimit(60s/30) + @Idempotent(5s) | `FavoriteRequest {noteId}` | `R<{favorited:true}>` |
| `DELETE` | `/api/social/favorite` | X-User-Id | 无（不对称） | `FavoriteRequest {noteId}` | `R<{unfavorited:true}>` |
| `GET` | `/api/social/favorite/status` | X-User-Id | — | `noteId` | `R<{favorited:boolean}>` |
| `GET` | `/api/social/favorite/list` | X-User-Id | — | page, size | `R<List<Long>>` |

---

## 3. 内部架构

### 3.1 组件图

```
Controller 层
├── FollowController (7 endpoints)  ─┬─ FollowService
├── LikeController (5 endpoints)    ─┼─ LikeService
└── FavoriteController (4 endpoints)─┴─ FavoriteService

Service 层
├── LikeService          → Redis Set (SADD/SREM + 反向索引)
│                         → MQ syncSend (失败回滚 Redis Lua)
├── FollowService        → Redis ZSet (ZADD/ZREM + INCR/DECR)
│                         → MySQL INSERT (自动提交，无 @Transactional)
└── FavoriteService      → Redis ZSet (ZADD/ZREM)
                          → MQ asyncSend (无回滚)

MQ Consumer（全部监听 SOCIAL_TOPIC，通过 Tag 分流）
├── LikeConsumer      (LIKE)       → INSERT t_like
├── UnlikeConsumer    (UNLIKE)     → DELETE t_like
├── FavoriteConsumer  (FAVORITE)   → INSERT t_favorite
├── UnfavoriteConsumer(UNFAVORITE) → DELETE t_favorite
├── FollowConsumer    (FOLLOW)     → INSERT t_follow    [@ConditionalOnProperty 默认禁用]
└── UnfollowConsumer  (UNFOLLOW)   → DELETE t_follow    [@ConditionalOnProperty 默认禁用]

定时任务
└── FollowCounterRepairJob (XXL-Job) → 每小时对账 Redis 计数
```

### 3.2 Redis Key 清单

| Key Pattern | 类型 | 用途 |
|-------------|:--:|------|
| `myxhs:follow:list:{userId}` | ZSet | 关注列表（score=关注时间戳） |
| `myxhs:follow:fans:{userId}` | ZSet | 粉丝列表 |
| `myxhs:counter:user_following:{userId}` | String | 关注数（INCR/DECR 维护，ZCARD 对账） |
| `myxhs:counter:user_follower:{userId}` | String | 粉丝数 |
| `myxhs:like:note:{noteId}` | Set | 笔记点赞用户集合 |
| `myxhs:like:comment:{commentId}` | Set | 评论点赞用户集合 |
| `myxhs:like:user:{userId}:note` | Set | 用户点赞笔记反向索引（"我点赞过哪些笔记"） |
| `myxhs:favorite:{userId}` | ZSet | 用户收藏列表（score=收藏时间戳） |

**反向索引**：Like 有（`myxhs:like:user:{userId}:note`），Favorite 没有（用 ZSet 天然支持 ZSCORE 查询状态），Follow 不需要（ZSet 的 ZSCORE 即可判互关）。

### 3.3 三种数据结构共存的原因

| 业务 | 结构 | 为什么 |
|------|:--:|------|
| Like 正向索引 | **Set** | 只需判存在 + 计数，不需要排序分页。Set 比 ZSet 省内存 |
| Like 反向索引 | **Set** | "我点赞过哪些笔记"不需要时间排序 |
| Follow 关系 | **ZSet** | 关注列表按时间倒序分页、互关需要 ZSCORE |
| Favorite 列表 | **ZSet** | 收藏列表按时间倒序分页、判状态需要 ZSCORE |

### 3.4 MQ Topic 矩阵

**唯一 Topic**：`SOCIAL_TOPIC`，通过 Tag 分流 6 个 Consumer：

| Consumer | Tag | 状态 | 幂等策略 |
|---------|:--:|:--:|------|
| LikeConsumer | LIKE | 始终启用 | DuplicateKeyException (uk_user_biz) |
| UnlikeConsumer | UNLIKE | 始终启用 | DELETE 天然幂等 |
| FavoriteConsumer | FAVORITE | 始终启用 | DuplicateKeyException (uk_user_note) |
| UnfavoriteConsumer | UNFAVORITE | 始终启用 | DELETE 天然幂等 |
| FollowConsumer | FOLLOW | **默认禁用** | DuplicateKeyException (uk_user_follow) |
| UnfollowConsumer | UNFOLLOW | **默认禁用** | DELETE 天然幂等 |

**FollowConsumer/UnfollowConsumer 为什么禁用？** FollowService 当前使用 Redis Lua + MySQL INSERT 同步双写（无 @Transactional）。改为 MQ 异步落库后需启用 Consumer，但需解决一致性风险——Redis 写入成功但 MQ 发送失败时无回滚机制（不含本地消息表）。

---

## 4. 业务代码详解

### 4.1 点赞 — `LikeService.like()`

**源码**：`my-xhs-analytics/.../service/LikeService.java`

```
like(userId, LikeRequest)
│
├─ 1. 构建 Redis Key
│     likeKey = "myxhs:like:note:{noteId}"
│     userLikeKey = "myxhs:like:user:{userId}:note"    ← 反向索引
│
├─ 2. 执行 like_atomic.lua（原子 SADD 正反向索引）
│     ARGV: member=userId, reverseMember=noteId, hasReverse="1"（笔记）
│     返回 0 → 已点赞（幂等）→ 直接返回
│     返回 1 → 新增成功
│
├─ 3. syncSend SOCIAL_TOPIC, tag=LIKE（同步发送，3s 超时）
│     ├─ 成功 → return {liked:true}
│     └─ 失败 → 执行 Lua 回滚（unlike_atomic.lua）→ throw BizException
│
└─ 4. Consumer: LikeConsumer → INSERT t_like
       catch DuplicateKeyException → 幂等
```

**为什么 syncSend 而不 asyncSend？** 点赞操作对一致性要求高。syncSend 失败立即回滚 Redis——保证"用户看到点赞状态"和"DB 记录"在同一时刻一致。如果 asyncSend，可能存在短暂的"Redis 显示已点赞但 DB 不显示"的窗口。

**为什么取消点赞用裸 SREM 不用 Lua？** 取消点赞只涉及一个 Set（`myxhs:like:note:{noteId}`），不涉及反向索引的 Lua 双写——`unlike_atomic.lua` 本身也执行 SREM 双写。

**双重幂等**：HTTP 层 @Idempotent(5s) 拦截网络重试，Redis 层 SADD return 0 拦截更长间隔的重复请求。

### 4.2 关注 — `FollowService.follow()`

**源码**：`my-xhs-analytics/.../service/FollowService.java`

```
follow(userId, targetUserId)
│
├─ 1. 校验 userId != targetUserId
│
├─ 2. Step A: follow_self.lua（关注者侧）
│     KEYS: followingKey, followingCountKey
│     ZSCORE 检查 → ZADD → INCR 计数
│     返回 0 → 已关注（幂等）→ return
│
├─ 3. Step B: follow_target.lua（目标用户侧）
│     KEYS: followerKey, followerCountKey
│     ZSCORE 检查 → ZADD → INCR 计数
│     try-catch 吞异常 → Step B 失败不影响整体返回
│
├─ 4. MySQL INSERT t_follow（自动提交，失败不影响 Redis 数据）
│
└─ 5. Step B 失败场景：
      关注者侧计数正确（+1），被关注者侧计数少 1
      → FollowCounterRepairJob（每小时）以 ZCARD 覆写 counter
```

**为什么拆分成两个脚本？** Redis Cluster 要求一个 EVAL 的所有 KEYS 在同一 slot。关注操作涉及两个用户的数据（关注者 + 被关注者），它们不可能在同一 slot——因此必须拆成两个脚本分别操作。

**已知隐患**：Lua 注释说用 `{userId}` hash tag 保证 Cluster 兼容，但实际 Java Key 构造没有加 `{...}`（`"myxhs:follow:list:" + userId`）。当前 Sentinel 环境运行正常，但迁移 Cluster 会报 CROSSSLOT 错误。

### 4.3 收藏 — `FavoriteService.favorite()`

```
favorite(userId, noteId)
│
├─ 1. favorite_atomic.lua: ZSCORE 检查 + ZADD（原子幂等）
│
├─ 2. asyncSend SOCIAL_TOPIC, tag=FAVORITE（异步 + callback）
│
└─ 3. Consumer: FavoriteConsumer → INSERT t_favorite
       catch DuplicateKeyException → 幂等
```

**取��收藏为什么不用 Lua？** 收藏只有一个 Key（`myxhs:favorite:{userId}`），ZREM 是单一 Redis 命令，天然原子，不需要 Lua 保证跨 Key 双写。

---

## 5. MQ Consumer 幂等模式

### 5.1 DuplicateKeyException 模式

所有 4 个 INSERT Consumer 使用统一模式：

```java
try {
    mapper.insert(entity);
} catch (DuplicateKeyException e) {
    log.debug("重复消费，幂等忽略");
}
```

| Consumer | 唯一索引 | 幂等保证 |
|---------|------|:--:|
| LikeConsumer | `uk_user_biz (user_id, biz_type, biz_id)` | ✅ |
| FavoriteConsumer | `uk_user_note (user_id, note_id)` | ✅ |
| FollowConsumer | `uk_user_follow (user_id, follow_user_id)` | ✅（默认禁用） |

### 5.2 异步落库的 createdAt 语义不一致

| Consumer | createdAt 来源 | 语义 | MQ 积压时偏差 |
|---------|------|------|:--:|
| LikeConsumer | `LocalDateTime.now()` | **消费时刻** | 有偏差 |
| FavoriteConsumer | `LocalDateTime.now()` | **消费时刻** | 有偏差 |
| FollowConsumer | `Instant.ofEpochMilli(event.actionTime)` | **事件发生时刻** | 无偏差 |

不一致现象：MQ 积压 10 分钟时，Like 的 createdAt 比实际点赞时间晚 10 分钟，而 Follow 的 createdAt 是准确的。

---

## 6. 定时对账

### FollowCounterRepairJob（XXL-Job）

```
每小时执行：
  lastId = 0
  loop:
    userIds = followMapper.selectDistinctUserIds(lastId, BATCH_SIZE=100)
    foreach userId:
      ZCARD(myxhs:follow:list:{userId}) → Redis 值
      counter 值 → GET 当前值
      不一致 → SET counter = ZCARD
    lastId = followMapper.selectMaxIdByLastId(lastId, BATCH_SIZE=100)
    if lastId == null → break
```

**已知弊端**：`selectMaxIdByLastId` 的 SQL `SELECT MAX(id) FROM t_follow WHERE id > #{lastId} LIMIT 100` 中 LIMIT 对 MAX 聚合函数无意义。当前不影响正确性（只是可能扫描到超出 BATCH=100 范围的 id），但暴露了 SQL 语义理解的偏差。

---

## 7. Gateway 交互

### 7.1 白名单情况

| 接口 | auth 白名单 |
|------|:--:|
| `/api/social/following/**` | 是 |
| `/api/social/follower/**` | 是 |
| `/api/social/like/count` | 是 |
| 其他 `/api/social/**` | 否（需要 JWT Token + HMAC 签名） |

### 7.2 Gateway 路由规则

```yaml
- id: analytics-service
  uri: lb://my-xhs-analytics
  predicates: Path=/api/analytics/**,/api/social/**
  metadata:
    response-timeout: 3000ms
    rate-limit-qps: 100
```

---

## 8. 资源配置

| 配置组 | 关键项 | 值 |
|--------|--------|------|
| Server | port | 19003 |
| Server | Tomcat threads | max=150 |
| Redis | Sentinel master | mymaster (26379/26380/26381) |
| Redis | cache.port | 16380（allkeys-lru）|
| Redis | business.port | 16381（noeviction）|
| RocketMQ | producer.group | analytics-producer-group |
| RocketMQ | send-message-timeout | 3000ms |
| XXL-Job | FollowCounterRepairJob | 建议每小时 |

---

## 9. 依赖分析

pom.xml 中 16 项依赖，大部分与 common 模块一致。特殊项：

| 依赖 | 用途 |
|------|------|
| `spring-boot-starter-data-redis` | Redis Sentinel 连接 + Lua 脚本执行 |
| `rocketmq-spring-boot-starter` | 6 个 Consumer + Producer |
| `xxl-job-core` | FollowCounterRepairJob 分布式调度 |
| `my-xhs-common` | RedisOperator, RedisKeyConstants, BizException, @RateLimit, @Idempotent |
| `my-xhs-user-api` | **模块不存在，死依赖**（同 content 模块） |
| `spring-cloud-starter-openfeign` | common 模块传递需求 |

---

## 10. 被下游调用

### 10.1 被 home BFF 通过 Feign 调用

```java
// my-xhs-home/.../feign/AnalyticsFeignClient.java
@FeignClient(name = "my-xhs-analytics", fallbackFactory = ...)
public interface AnalyticsFeignClient {
    @GetMapping("/api/social/like/count")
    R<Map<String, Object>> getLikeCount(@RequestParam bizType, @RequestParam bizId);
}
```

home 模块在聚合笔记详情时，需要附带点赞数，通过此 Feign 接口调用。

---

## 11. 已知问题与改进

### 11.1 🔴 严重

| 问题 | 详情 |
|------|------|
| **M6 hash tag 未实现** | Lua 注释声称用 `{userId}` hash tag 保证 Cluster 兼容，但实际 Java Key 构造（`"myxhs:follow:list:" + userId`）缺少 `{...}`，迁移 Redis Cluster 会报 CROSSSLOT |
| **FollowService 无 @Transactional** | Redis Lua 和 MySQL INSERT 独立执行，无共享事务边界。Step A 成功但 Step B 失败时无回滚 |

### 11.2 🟡 中等

| 问题 | 详情 |
|------|------|
| **DELETE unlike/unfavorite 缺少 @RateLimit/@Idempotent** | POST 有限流和幂等保护，DELETE 全裸——可被刷取关攻击 |
| **selectMaxIdByLastId SQL 中 LIMIT 无意义** | `SELECT MAX(id) ... LIMIT 100` — LIMIT 对 MAX 聚合无效，虽然当前不影响正确性 |
| **createdAt 语义不一致** | Like 用 Consumer 消费时刻，Follow 用事件发生时刻——MQ 积压时偏差 |
| **LikeEvent Tag 与 payload.action 无交叉校验** | MQ Tag=LIKE 但 event.action="UNLIKE" 时照样 INSERT——缺少契约验证 |
| **LikeService.unlike Javadoc 与实际代码矛盾** | Javadoc 说"不发 MQ"，实际代码 syncSend UNLIKE |

### 11.3 🟢 提示

| 问题 | 详情 |
|------|------|
| 评论点赞没有反向索引 | `bizType=2` 时反向索引 Key 设为 "noop"——人为跳过 SADD |
| Event 中 eventId/timestamp 未在 Consumer 使用 | ~50 字节的冗余 MQ 消息体 |
| FollowConsumer/UnfollowConsumer 默认禁用但有完整代码 | 待启用时需验证一致性风险 |

---

## 12. 模块文件清单

| 类别 | 数量 | 关键文件 |
|------|:--:|------|
| Controller | 3 | FollowController, LikeController, FavoriteController |
| Service | 3 | FollowService, LikeService, FavoriteService |
| Consumer | 6 | Like/Unlike/Follow/Unfollow/Favorite/Unfavorite (SOCIAL_TOPIC) |
| Entity | 3 | Follow, Like, Favorite |
| Mapper | 3 | FollowMapper（3 个自定义 SQL）, LikeMapper, FavoriteMapper |
| DTO | 6 | 3 Event + 2 Request + 1 VO |
| Lua 脚本 | 7 | like_atomic, unlike_atomic, follow_self/target, unfollow_self/target, favorite_atomic |
| Config | 1 | RedisScriptConfig（7 个 DefaultRedisScript Bean） |
| Job | 1 | FollowCounterRepairJob（XXL-Job） |
| 资源文件 | 3 | application.yml, application-datasource.properties, logback-spring.xml |
| **总计** | **36** | |

## 关联文档

- `/data/workspace/my-xhs/docs/test-2/03-analytics/02-analytics-test.md` — curl 逐条测试规划（19 个测试点 + 16 个工程主题）
