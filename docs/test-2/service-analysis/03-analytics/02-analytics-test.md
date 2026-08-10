# 数据分析模块 curl 逐条测试规划

> 每条测试包含：curl 请求、预期返回、DB 验证、Redis 验证、工程知识讲解。

## 测试总览

| 分组 | 编号 | 接口 | 优先级 |
|------|:--:|------|:--:|
| **第1组: ￿关注** | 4.1 | POST /api/social/follow/{targetUserId} | P0 |
| | 4.2 | DELETE /api/social/follow/{targetUserId} | P0 |
| | 4.3 | GET /api/social/following/{userId} | P1 |
| | 4.4 | GET /api/social/follower/{userId} | P1 |
| | 4.5 | GET /api/social/common/{targetUserId} | P2 |
| | 4.6 | GET /api/social/relation/{targetUserId} | P2 |
| **第2组: 点赞** | 4.7 | POST /api/social/like | P0 |
| | 4.8 | DELETE /api/social/like | P0 |
| | 4.9 | GET /api/social/like/status | P1 |
| | 4.10 | GET /api/social/like/batch-status | P1 |
| | 4.11 | GET /api/social/like/count | P1 |
| **第3组: 收藏** | 4.12 | POST /api/social/favorite | P0 |
| | 4.13 | DELETE /api/social/favorite | P0 |
| | 4.14 | GET /api/social/favorite/status | P1 |
| | 4.15 | GET /api/social/favorite/list | P1 |
| **第4组: MQ + 对账** | 4.16 | MQ SOCIAL_TOPIC 消费验证 | P1 |
| | 4.17 | FollowCounterRepairJob 对账 | P2 |
| | 4.18 | 幂等性验证 (DuplicateKeyException) | P1 |
| | 4.19 | POST /api/social/internal/repair-counter/{userId} | P2 |

## 测试环境

- 服务: `http://localhost:19003/api/social`
- MySQL: `mysql -h 21.130.247.89 -P 13306 -u root -p'Xhs@2026#MySQL' my_xhs_analytics`
- Redis: `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True)"`

## 测试前置条件

测试前需准备的测试数据：

| 条件 | 说明 |
|------|------|
| 用户 testuser (10001) | 测试用户，用于发起关注/点赞/收藏 |
| 用户 newuser (2078387513547841537) | 已存在的用户，作为被关注/被点赞对象 |
| 笔记 id=2078408307372003329 | 已发布的笔记（content 模块测试产出的"已编辑的测试笔记"） |
| content 服务运行 | 点赞/收藏需要笔记存在 |

## Redis Key 速查表

| Key Pattern | 类型 | 用途 |
|-------------|:--:|------|
| `myxhs:follow:list:{userId}` | ZSet | 关注列表（score=关注时间） |
| `myxhs:follow:fans:{userId}` | ZSet | 粉丝列表 |
| `myxhs:counter:user_following:{userId}` | String | 关注数 |
| `myxhs:counter:user_follower:{userId}` | String | 粉丝数 |
| `myxhs:like:note:{noteId}` | Set | 笔记点赞用户集合 |
| `myxhs:like:comment:{commentId}` | Set | 评论点赞用户集合 |
| `myxhs:like:user:{userId}:note` | Set | 用户点赞笔记反向索引 |
| `myxhs:favorite:{userId}` | ZSet | 用户收藏列表（score=收藏时间） |

## 架构概览

### 三条业务线

```
                        Controller
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
    LikeService        FollowService      FavoriteService
         │                  │                  │
    ┌────┴────┐     ┌──────┴──────┐     ┌────┴────┐
    │         │     │             │     │         │
  Redis     MQ   Redis          DB   Redis      MQ
  Set    SOCIAL  ZSet        (同步写) ZSet    SOCIAL
 SADD/   _TOPIC ZADD/       t_follow ZADD/   _TOPIC
 SREM    sync  ZREM         (不走MQ) ZREM    async
 (权威)  Send  (权威)               (权威)   Send
  +反向 失败回 (自侧+目标侧         (无回滚)  +callback
  索引   滚Lua  分别Lua)
         │     ┌─────┐              │         │
      Consumer │自侧 │         FollowConsumer Consumer
      (落MySQL)│成功 │         (@Conditional  (落MySQL)
    Duplicate │……→ 目标侧失败?    OnProperty    Duplicate
    Key异常   │     │              默认禁用)    Key异常
   幂等       │     └→计数不一致
              │         ↓
              │   FollowCounterRepairJob
              │   (XXL-Job, 每小时)
              │   以 ZCARD 纠正 counter
              │
         SOCIAL_TOPIC tag 分流
         LIKE/UNLIKE/FOLLOW/UNFOLLOW/FAVORITE/UNFAVORITE
```

### 三层数据保障 + 运行模式差异

| 层 | Like | Follow | Favorite |
|:--:|:--:|:--:|:--:|
| **L1 — Redis（实时读写）** | SADD/SREM + 反向索引 | ZADD/ZREM + INCR/DECR | ZADD/ZREM |
| **L2 — 持久化** | syncSend SOCIAL_TOPIC → Consumer 落库 | 独立 Redis Lua + MySQL INSERT（无 @Transactional，自动提交） | asyncSend SOCIAL_TOPIC → Consumer 落库 |
| **L2 失败处理** | 失败回滚 Redis Lua | MySQL INSERT 失败不影响 Redis（对账修复） | callback 记日志，无回滚 |
| **L3 — 定时对账** | ❌ | ✅ FollowCounterRepairJob (ZCARD 覆写 counter) | ❌ |

**关键差异**：
- Like 的 syncSend 失败回滚 Redis——因为点赞是先写 Redis、再发 MQ 落 DB。如果 MQ 失败但 Redis 已写入，Redis 和 DB 不一致。回滚保证一致性。
- Follow 的 Redis Lua 和 MySQL insert **独立执行，无共享事务边界**（FollowService 无 `@Transactional`）。Step A（关注者侧 Lua）成功但 Step B（目标侧 Lua）失败时，计数不一致——由 XXL-Job 对账修复。MySQL insert 为尽力写入，失败不影响 Redis 数据。
- Favorite 的 asyncSend 无回滚——因为收藏不像关注有计数一致性要求。ZADD 成功即可，DB 最终一致。

### FollowConsumer/UnfollowConsumer 默认禁用的原因

FollowService 使用**同步 INSERT**写 MySQL（自动提交，无 `@Transactional`），不走 MQ 异步落库。这两个 Consumer 带有 `@ConditionalOnProperty`，仅在显式配置启用时才工作。

### 需要深入讲解的工程主题

| 主题 | 涉及的接口 | 深度文档建议 | 关键知识点 |
|------|---------|------|------|
| Lua 脚本原子操作 + Redis Cluster 兼容 | 4.1/4.2/4.7/4.8/4.12 | 03-lua-atomic-scripts/ | 7 个 Lua 脚本预加载、SISMEMBER/SISMEMBER+ZSCORE 原子语义、hash tag 保证同 slot |
| 关注两阶段 Lua（M6 修复） | 4.1/4.2 | 03b-follow-two-phase-lua/ | follow_self 后 follow_target 失败 → 计数不一致 → 对账修复 |
| syncSend 失败回滚 Redis | 4.7/4.8 | 04-mq-reliability/ | 先写 Redis → syncSend → 失败执行 Lua 回滚 |
| DuplicateKeyException 幂等模式 | 4.16/4.18 | 05-idempotency-pattern/ | MySQL 唯一索引 + catch DuplicateKeyException = 幂等 |
| Pipeline 批量优化 | 4.10/4.3/4.4 | 06-pipeline-optimization/ | 互关状态查询避免 N+1，一次往返批量 SISMEMBER |
| ZINTER 服务端交集 | 4.5 | 07-redis-set-operations/ | 共同关注不用拖回 5000x2 数据 |
| XXL-Job 对账修复 | 4.17/4.19 | 08-counter-repair/ | 每小时扫描 t_follow → 游标分页 → repairUserCounters → ZCARD 覆写 |
| ZSet 关注关系设计 | 4.1~4.6 | 09-social-graph-design/ | 双向关系存储 + Pipeline 互关 + 计数一致性权衡 |
| Like(Set) vs Follow(ZSet) vs Favorite(ZSet) | 2.1~2.3 | 10-data-structure-design/ | 三种业务三种数据结构：Set 省内存、ZSet 支持排序分页、counter String vs SCARD 一致性取舍 |
| 双重幂等：@Idempotent(HTTP) + Lua(Redis) | 4.7/4.12 | common 模块 + 03-lua | 两层解决不同 failure mode：HTTP 5s防重试 vs Lua 永久防重复 |
| Favorite unfavorite 无 Lua | 4.13 | 03-lua | 为什么取消收藏用裸 ZREM——无交叉key操作时不需要 Lua |
| FollowConsumer 默认禁用 | 4.16 | 05-idempotency | @ConditionalOnProperty 默认关闭，原因是当前 FollowService 用同步模式 |
| createdAt 语义不一致 | 4.16/4.18 | 05-idempotency | Like 记录 Consumer 消费时刻，Follow 记录事件发生时刻——MQ 积压时产生偏差 |
| M6 Redis Cluster hash tag 未实现 | 4.1/4.2 | 03-lua | Lua 注释声称用 {userId} hash tag 但实际 Key 构造缺失——Sentinel 环境未暴露

---

## 4.1 关注 — `POST /api/social/follow/{targetUserId}`

### curl 请求

```bash
curl -s -X POST "http://localhost:19003/api/social/follow/2078387513547841537" \
  -H "X-User-Id: 10001"
```

### 实际返回

```json
{ "code": 41001, "message": "已关注该用户" }
```

### 幂等验证

返回 `code=41001`（已关注）——content 模块的 Feed 测试中已建立 `testuser(10001) 关注 newuser(2078387513547841537)`。`follow_self.lua` 中 ZSCORE 预检查拦截了重复请求。

### Redis 验证

| 验证项 | 值 |
|--------|------|
| `myxhs:follow:list:10001` ZSet | targetUserId=2078387513547841537, score=1784775443191 |
| `myxhs:counter:user_following:10001` | 1 |
| `myxhs:follow:fans:2078387513547841537` | followerUserId=10001 |
| `myxhs:counter:user_follower:2078387513547841537` | 1 |

### MySQL 验证

| user_id | follow_user_id | created_at |
|---------|---------------|------------|
| 10001 | 2078387513547841537 | 2026-07-23 10:57:23 |

### 业务讲解

`follow_self.lua` 中的 ZSCORE 预检查：已存在 → 返回 0 → Service 返回"已关注"。双重幂等第一层（Redis Lua）生效。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 幂等返回 "已关注" | code=41001 | 通过 |
| Redis ZSet + counter = 1 | ✅ | 通过 |
| MySQL 有记录 | ✅ | 通过 |

---

## 4.2 取关 — `DELETE /api/social/follow/{targetUserId}`

### curl 请求

```bash
curl -s -X DELETE "http://localhost:19003/api/social/follow/2078387513547841537" \
  -H "X-User-Id: 10001"
```

### 实际返回

```json
{ "code": 200, "message": "取关成功" }
```

### 三层验证

| 层 | 取关前 | 取关后 |
|----|--------|--------|
| Redis ZSet | 1 member | **0 members** |
| Redis counter | 1 | **0**（DECR 含 >0 防护） |
| MySQL | 1 row | **0 rows**（DELETE 天然幂等） |

### 业务讲解

```
unfollow(userId=10001, targetUserId=2078387513547841537)
│
├─ 1. Step A: unfollow_self.lua → ZREM + DECR（含 >0 防护）
│     返回 0 → 未关注（幂等）
│
├─ 2. Step B: unfollow_target.lua → ZREM + DECR
│     try-catch 吞异常
│
└─ 3. MySQL DELETE（自动提交）
```

**Delete 幂等**：ZREM/DELETE 本身就是幂等操作——重复执行不报错。

## 4.3/4.4 关注列表 + 粉丝列表 — 公开接口

```bash
curl -s "http://localhost:19000/api/social/following/10001?page=1&size=10"
curl -s "http://localhost:19000/api/social/follower/2078387513547841537?page=1&size=10"
```

取关后返回 `{"total":0,"list":[]}`。重新关注后返回 userId + followedAt + isFollowBack。

## 4.6 关注关系查询 — 重新关注后验证

```json
{
  "isFollowing": true,
  "isFollowBack": false,
  "isMutual": false
}
```

| 字段 | 含义 |
|------|------|
| isFollowing | 我是否关注了对方 ✅ |
| isFollowBack | 对方是否关注了我 → 否 |
| isMutual | 互关（`isFollowing && isFollowBack`）→ 否 |

### 期望 vs 实际（4.2~4.6）

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取关后三层全空 | Redis ZSet=0 / counter=0 / MySQL=0 | 通过 |
| 公开接口无需 Token | code=200 | 通过 |
| 重新关注后 isFollowing=true | ✅ | 通过 |

---

## 4.5 共同关注 — `GET /api/social/common/{targetUserId}`

### curl 请求

```bash
curl -s "http://localhost:19003/api/social/common/10002" -H "X-User-Id: 10001"
```

### 实际返回

```json
[2078387513547841537]
```

### 验证

testuser(10001) 关注 {newuser(2078387513547841537)}，testuser2(10002) 也关注 {newuser}，交集 = {newuser}。ZINTER 在 Redis 服务端计算交集，一次网络往返返回结果。

### 业务讲解

```java
// FollowService.getCommonFollowing()
String myKey = "myxhs:follow:list:10001";
String targetKey = "myxhs:follow:list:10002";
// ZINTER 2 myKey targetKey → [2078387513547841537]
```

不用把两边的 5000x2 数据拖回内存再算交集——ZINTER 在 Redis 服务端完成。

## 4.19 手动对账修复 — `POST /api/social/internal/repair-counter/{userId}`

### 实际返回

```json
{ "code": 200 }
```

触发 `FollowService.repairUserCounters()` + `repairUserRelationships()` 对 userId=10001 执行全量对账。

### 期望 vs 实际（4.5/4.19）

| 期望 | 实际 | 结论 |
|------|------|:----:|
| ZINTER 返回共同关注 | [2078387513547841537] | 通过 |
| 手动对账执行成功 | code=200 | 通过 |

---

## 4.7 点赞 — `POST /api/social/like`

### curl 请求

```bash
curl -s -X POST "http://localhost:19003/api/social/like" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"bizType":1,"bizId":2078408307372003329}'
```

### 实际返回

```json
{ "code": 200, "message": "点赞成功" }
```

### Redis 验证

| 验证项 | 值 |
|--------|------|
| 正向索引 `myxhs:like:note:2078408307372003329` | Set {10001} |
| 反向索引 `myxhs:like:user:10001:note` | Set {2078408307372003329, 2076182853659451393}（含之前的点赞） |
| SCARD | 1 |

### MySQL 验证

| user_id | biz_type | biz_id | created_at |
|---------|----------|--------|------------|
| 10001 | 1 | 2078408307372003329 | 2026-07-24 11:32:45 |

### 幂等验证

重复 POST 相同参数 → `code=200, message="点赞成功"` — Redis Lua SADD return 0（已存在）→ 不重复写入。

### 业务讲解

```
like(userId=10001, LikeRequest{bizType=1, bizId=2078408307372003329})
│
├─ 1. like_atomic.lua: SADD 正向 + SADD 反向索引（一次 EVAL，原子）
│     ARGV[3]=hasReverse="1"（笔记需要反向索引）
│     返回 1 → 新增
│
├─ 2. syncSend SOCIAL_TOPIC tag=LIKE（3s超时）
│     ├─ 成功 → {liked:true}
│     └─ 失败 → unlike_atomic.lua 回滚 → throw BizException
│
└─ 3. LikeConsumer → INSERT t_like（DuplicateKeyException 幂等）
```

**双层幂等**：@Idempotent(5s) + Lua SADD return 0。

## 4.8 取消点赞 — `DELETE /api/social/like`

```json
{ "code": 200, "message": "取消点赞成功" }
```

取消后：正向索引 Set = empty，反向索引剔除该笔记，MySQL t_like count=0。

### 期望 vs 实际（4.7~4.8）

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 点赞写入 Redis 正反向索引 | ✅ Set {10001} | 通过 |
| MySQL 落库成功 | ✅ t_like 有记录 | 通过 |
| 幂等：重复点赞不报错 | code=200 | 通过 |
| 取消后四层清空 | Set empty / MySQL count=0 | 通过 |

## 4.9~4.11 点赞查询

| 接口 | 结果 | 说明 |
|------|------|------|
| `GET /like/status` | false | 取消点赞后检查状态 |
| `GET /like/batch-status?bizIds=a,b` | {a:false, b:true} | Pipeline 批量 SISMEMBER |
| `GET /like/count` | 1 | SCARD 实时查询 |

重新点赞后 count=1，与 Redis SCARD 一致。

## 4.12~4.15 收藏

| 接口 | 结果 | 说明 |
|------|------|------|
| `POST /favorite` | 200 收藏成功 | ZSet ZADD + asyncSend |
| Redis ZSet | noteId=2078408307372003329, score=1784864024767 | score=收藏时间戳 |
| MySQL | t_favorite 有对应行 | Consumer 异步落库 |
| `DELETE /favorite` | 200 取消成功 | 裸 ZREM（不需要 Lua） |
| 取消后 Redis ZCARD | 1→0 | 减去新收藏的 |

**Favorite 不用 Lua 的原因**：只有一个 Key（`myxhs:favorite:{userId}`），ZREM 是单一 Redis 命令，天然原子。

**asyncSend 无回滚**：收藏没有计数器需要维护（计数用 ZCARD），Redis 是权威数据源，MQ 偶尔丢失可接受。
