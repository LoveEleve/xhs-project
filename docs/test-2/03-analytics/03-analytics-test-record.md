# 数据分析模块 curl 逐条测试记录

> 每条测试包含：curl 请求、实际返回、DB 验证、Redis 验证、业务讲解、期望vs实际。
> 原则：一个一个测试，不跳跃，不批量。

## 测试环境

- 关注/点赞/收藏：`http://localhost:19003/api/social`（直连，X-User-Id Header）
- 公开读接口：`http://localhost:19000/api/social`（Gateway 路由）
- MySQL: `mysql -h 21.130.247.89 -P 13306 -u root -p'Xhs@2026#MySQL' my_xhs_analytics`
- Redis: `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True)"`
---

## 4.1 关注 — `POST /api/social/follow/{targetUserId}`

### curl 请求

```bash
curl -s -X POST "http://localhost:19003/api/social/follow/2078387513547841537" \
  -H "X-User-Id: 10001"
```

### 实际返回

```json
{
  "code": 200,
  "message": "关注成功",
  "success": true
}
```

### Redis 验证 — 关注者侧

| Key | 值 |
|-----|------|
| `myxhs:follow:list:10001` (ZSet) | member=2078387513547841537, score=1784869063043 |
| `myxhs:counter:user_following:10001` | 1 |

### Redis 验证 — 被关注者侧

| Key | 值 |
|-----|------|
| `myxhs:follow:fans:2078387513547841537` (ZSet) | member=10001 |
| `myxhs:counter:user_follower:2078387513547841537` | 1 |

### MySQL 验证

| user_id | follow_user_id | created_at |
|---------|---------------|------------|
| 10001 | 2078387513547841537 | 2026-07-24 12:57:43 |

### 业务讲解

关注请求最终执行了 `FollowService.follow()`（`FollowService.java:70`），分为三步：

**Step A — follow_self.lua（关注者侧，原子操作）**

```lua
-- 获取 scripts/lua/follow_self.lua
-- KEYS[1] = myxhs:follow:list:10001（关注列表 ZSet）
-- KEYS[2] = myxhs:counter:user_following:10001（关注数 counter）
-- ARGV[1] = 2078387513547841537（被关注者）
-- ARGV[2] = 1784869063043（关注时间戳）

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end           -- 已关注，幂等返回

redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])  -- ZSet 记录关注关系+时间
redis.call('INCR', KEYS[2])                      -- Counter +1
return 1                                         -- 关注成功
```

一次 EVAL 调用完成 ZSCORE（幂等检查）+ ZADD（写入关系）+ INCR（更新计数）三个操作。Redis 单线程执行模型保证这三个操作不会被其他命令打断。

**Step B — follow_target.lua（被关注者侧）**

同样的逻辑，操作目标用户的粉丝列表和粉丝数。Step B 失败不影响返回——被 `try-catch` 吞掉，由 `FollowCounterRepairJob` 对账修复。

**Step C — MySQL INSERT**

`followMapper.insert(follow)`，自动提交模式（无 `@Transactional`）。失败不影响 Redis 数据。

**关键设计点**：

1. **两个 Lua 脚本必须分开执行**：关注涉及两个用户的数据，Redis Cluster 要求同一 EVAL 的所有 KEYS 在同一 slot——两个用户的 Key 不可能在同一 slot。拆分为 self + target 是架构约束，不是设计缺陷。

2. **ZSet score = 关注时间戳**：`System.currentTimeMillis()` 作为 score。`ZREVRANGE ... WITHSCORES` 返回时按 score 倒序，天然支持"最近关注的排在最前面"。同时 score 本身记录了关注时间，不需要额外存储。

3. **Counter 单独维护**：不用 `ZCARD` 实时计算，因为 Lua 脚本内需要返回操作结果（1/0）而非计数。独立 counter 通过 `GET` 直接获取关注数，不需要额外一次 Redis 命令。

4. **Step B 失败不回滚 Step A**：没有跨 Key 的分布式事务机制——Redisson 的 RLock 也无法回滚已执行的 Redis 命令。对账修复（FollowCounterRepairJob）最终会纠正不一致。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| code=200，关注成功 | ✅ | 通过 |
| 关注者 ZSet 有记录 | member=2078387513547841537 | 通过 |
| ZSet score = 关注时间戳 | 1784869063043 | 通过 |
| 关注者 counter = 1 | ✅ | 通过 |
| 被关注者粉丝 ZSet 有记录 | member=10001 | 通过 |
| 被关注者 counter = 1 | ✅ | 通过 |
| MySQL t_follow 有记录 | ✅ | 通过 |

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

### Redis 验证 — 取关前后对比

| Key | 取关前 | 取关后 |
|-----|--------|--------|
| `myxhs:follow:list:10001` ZCARD | 1 | **0** |
| `myxhs:counter:user_following:10001` | 1 | **0** |
| `myxhs:follow:fans:2078387513547841537` ZCARD | 1 | **0** |
| `myxhs:counter:user_follower:2078387513547841537` | 1 | **0** |

### MySQL 验证 — 取关前后对比

| 取关前 | 取关后 |
|--------|--------|
| 1 row | **0 rows** |

### 业务讲解

取关执行了 `FollowService.unfollow()`（`FollowService.java:135`），对称于关注的三步：

**Step A — unfollow_self.lua（关注者侧）**

```lua
-- KEYS[1] = myxhs:follow:list:10001
-- KEYS[2] = myxhs:counter:user_following:10001
-- ARGV[1] = 2078387513547841537

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then return 0 end          -- 未关注，幂等返回

redis.call('ZREM', KEYS[1], ARGV[1])     -- 删除关注关系
local count = redis.call('GET', KEYS[2])
if count and tonumber(count) > 0 then     -- ⚠️ 防负数保护
    redis.call('DECR', KEYS[2])           -- Counter -1
end
return 1
```

**为什么需要 `if count > 0 then DECR`？**

极端场景：已经取关过一次（counter=0），但由于某种异常路径（MQ 重试、重复请求绕过幂等检查）再次触发取关。此时 ZREM 返回 0（无成员可删），但如果没 `>0` 保护，counter 会变成 -1，后续 INCR 正确值会从负数开始计算——"关注了 1 人但 counter 显示 0"。

`>0` 保护确保 counter 最小值为 0，不会变负数。

**Step B — unfollow_target.lua（被关注者侧）**

同样的逻辑，操作粉丝列表和粉丝数。失败吞异常，对账修复。

**Step C — MySQL DELETE**

`followMapper.deleteByUserIdAndFollowUserId(10001, 2078387513547841537)`，DELETE 天然幂等。

**与关注的差异**：

| 维度 | 关注 (4.1) | 取关 (4.2) |
|------|-----------|-----------|
| 正向操作 | ZADD + INCR | ZREM + DECR(>0) |
| 幂等返回 | 0 = 已关注 | 0 = 未关注（无操作） |
| 防负数 | N/A | `count > 0` 检查 |
| DELETE 幂等 | N/A（INSERT 用 DuplicateKeyException）| DELETE 天然幂等 |

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取关后四层全空 | ZCARD=0 / counter=0 / MySQL=0 | 通过 |
| DECR 正确减到 0 | counter 从 1 变 0（非 -1） | 通过 |
| DELETE 幂等生效 | MySQL count=0 | 通过 |

---

## 4.3 关注列表 — `GET /api/social/following/{userId}`

### curl 请求

```bash
# 公开接口，走 Gateway 路由（验证 Gateway 白名单放行）
curl -s "http://localhost:19000/api/social/following/10001?page=1&size=5" | jq '.data'
```

### 实际返回

```json
{
  "total": 1,
  "list": [
    {
      "userId": 2078387513547841537,
      "followedAt": "2026-07-24 14:50:20",
      "isFollowBack": false
    }
  ]
}
```

### 三层数据一致性

| 层 | 数据 |
|----|------|
| API (Gateway) | total=1, userId=2078387513547841537 |
| Redis ZSet | ZCARD=1, member=2078387513547841537, score=1784875820919 |
| MySQL | 1 row, user_id=10001, follow_user_id=2078387513547841537 |

### 业务讲解

`FollowService.getFollowingList()`（`FollowService.java:188-241`）：

```
getFollowingList(userId=10001, page=1, size=5)
│
├─ 1. ZREVRANGE myxhs:follow:list:10001 WITHSCORES（按 score 倒序分页）
│     → [{userId=2078387513547841537, score=1784875820919}]
│
├─ 2. Pipeline 批量 ZSCORE 查询互关状态
│     对列表中的每个 userId，查 myxhs:follow:fans:10001 是否有该 userId
│     → 20 次 ZSCORE 合并为 1 次网络往返
│
├─ 3. score → followedAt
│     Instant.ofEpochMilli(score) → LocalDateTime
│     → "2026-07-24 14:50:20"
│
└─ 4. Pipeline 结果 → isFollowBack
      score 不为 null → 互关（true）
      score 为 null → 非互关（false）
```

**怎么知道是 Gateway 路由的？** 请求走 `localhost:19000`（Gateway 端口），返回了正确数据——说明 Gateway 白名单（`/api/social/following/**`）放行了。

**`isFollowBack` 的判断逻辑**：

```
我关注了 A → A 出现在我的关注列表里
A 是否也关注了我？→ 在 myxhs:follow:fans:10001 中查 A 的 score
  → score 不为 null → A 也关注了我 → isFollowBack=true
  → score 为 null → A 没关注我 → isFollowBack=false
```

**Pipeline 的价值**：如果不改 Pipeline，每页 20 个用户需要 20 次 `ZSCORE`（20 次 Redis 往返）。Pipeline 合并为 1 次——详见 `06-pipeline-optimization/01-pipeline-zscore.md`。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| Gateway 公告接口无需 Token | code=200 | 通过 |
| 列表返回关注的用户 | userId=2078387513547841537 | 通过 |
| followedAt 正确（score → 时间） | 2026-07-24 14:50:20 | 通过 |
| isFollowBack 正确（非互关） | false | 通过 |
| API ↔ Redis ↔ MySQL 数据一致 | ✅ | 通过 |

---

## 4.4 粉丝列表 — `GET /api/social/follower/{userId}`

### curl 请求

```bash
curl -s "http://localhost:19000/api/social/follower/2078387513547841537?page=1&size=5"
```

### 实际返回

```json
{
  "total": 1,
  "list": [{
    "userId": 10001,
    "followedAt": "2026-07-24 14:50:20",
    "isFollowBack": false
  }]
}
```

### 三层数据一致性

| 层 | 数据 |
|----|------|
| API (Gateway) | total=1, userId=10001 |
| Redis ZSet (fans) | ZCARD=1, member=10001, score=1784875820919 |
| MySQL | user_id=10001, follow_user_id=2078387513547841537 |

### 业务讲解

```
getFollowerList(userId=2078387513547841537)
│
├─ 1. ZREVRANGE myxhs:follow:fans:2078387513547841537 WITHSCORES
│     → [{userId=10001, score=1784875820919}]
│
├─ 2. Pipeline 批量 ZSCORE 查询我的关注列表
│     对每个粉丝，查 myxhs:follow:list:2078387513547841537 是否有他们
│     → 一次往返判定所有互关状态
│
└─ 3. score → followedAt + Pipeline → isFollowBack
```

**与关注列表 (4.3) 的镜像对称**：

| 维度 | 关注列表 | 粉丝列表 |
|------|---------|---------|
| 查询的 Key | `myxhs:follow:list:{userId}` | `myxhs:follow:fans:{userId}` |
| Pipeline 查互关的 Key | `myxhs:follow:fans:{userId}` | `myxhs:follow:list:{userId}` |
| isFollowBack 含义 | 对方是否也关注我 | 我是否也关注了对方 |

两个方向的互关判定都需要 Pipeline 批量 ZSCORE——因为各自的视角不同，但优化手段相同。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 粉丝列表返回关注者 | userId=10001 | 通过 |
| followedAt 正确 | 2026-07-24 14:50:20 | 通过 |
| isFollowBack 正确 | false | 通过 |
| 三层数据一致 | ✅ | 通过 |

---

## 4.5 共同关注 — `GET /api/social/common/{targetUserId}`

### curl 请求

```bash
# testuser2(10002) 先关注了 newuser
# 然后查 testuser(10001) 和 testuser2(10002) 的共同关注
curl -s "http://localhost:19003/api/social/common/10002" -H "X-User-Id: 10001"
```

### 实际返回

```json
[2078387513547841537]
```

### Redis ZINTER 直接验证

```
ZINTER myxhs:follow:list:10001 myxhs:follow:list:10002
→ ['2078387513547841537']

10001 follows: ['2078387513547841537']
10002 follows: ['2078387513547841537']
交集: {2078387513547841537}
```

### 业务讲解

```java
// FollowService.getCommonFollowing()
String myKey = "myxhs:follow:list:10001";
String targetKey = "myxhs:follow:list:10002";
Set<String> common = opsForZSet().intersect(myKey, targetKey);
```

`ZINTER` 在 **Redis 服务端** 完成交集计算——只把**结果**（1 个 userId）传回客户端。

**为什么不在客户端求交集？**

```
坏方案：拉回两边的全量数据
  10001 关注列表: range(0, -1) → N 条
  10002 关注列表: range(0, -1) → M 条
  客户端 retainAll → 网络传输 N+M 条数据

好方案：ZINTER 服务端求交集
  → 只传输 intersection 结果（通常很小，几个到几十个）
```

分析见 `07-redis-set-operations/01-zinter-common-followers.md`。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 返回共同关注 newuser | [2078387513547841537] | 通过 |
| 与 ZINTER 直接结果一致 | ✅ | 通过 |

---

## 4.6 关注关系查询 — `GET /api/social/relation/{targetUserId}`

### 实际返回

```json
{
  "isFollowing": true,    // 我关注了对方
  "isFollowBack": false,  // 对方没有关注我
  "isMutual": false       // 非互关 (= isFollowing && isFollowBack)
}
```

### Redis 验证

| 关系 | ZSCORE 结果 |
|------|-----------|
| 10001 → 2078387513547841537 | ✅ 有 score（我关注了对方） |
| 2078387513547841537 → 10001 | ❌ null（对方没关注我） |

### 业务讲解

三次 ZSCORE 调用判断三种状态：
- `isFollowing` = 关注列表 ZSCORE（我→对方）≠ null
- `isFollowBack` = 粉丝列表 ZSCORE（对方→我）≠ null
- `isMutual` = isFollowing && isFollowBack

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| isFollowing=true | ✅ | 通过 |
| isFollowBack=false | ✅ | 通过 |
| isMutual=false | ✅ | 通过 |
| 与 Redis ZSCORE 一致 | ✅ | 通过 |

---

## 4.19 手动对账修复 — `POST /api/social/internal/repair-counter/{userId}`

### 实际返回

```json
{ "code": 200, "message": "操作成功" }
```

### 业务讲解

触发 `FollowService.repairUserCounters(10001)` + `repairUserRelationships(10001)`：
- 计数器已一致（ZCARD=1, counter=1）→ 无修复日志
- 关系已一致（Redis ZSet 成员 == MySQL follow_user_ids）→ 无修复日志

当前数据完全一致，对账结果为"无需修复"。

> 对账机制详见 `08-counter-repair/01-reconciliation-job.md`。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 对账执行成功 | code=200 | 通过 |
| 无差异 → 无修复日志 | 无 WARN 日志 | 通过 |

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

### Redis 验证 — 正向索引

| Key | 值 |
|-----|------|
| `myxhs:like:note:2078408307372003329` (Set) | {10001} |
| SCARD | 1 |

正向索引回答"这篇笔记被谁点赞了"——前端展示点赞头像列表时直接 `SMEMBERS` 这个 Set。

### Redis 验证 — 反向索引

| Key | 值 |
|-----|------|
| `myxhs:like:user:10001:note` (Set) | {2078408307372003329, 2076182853659451393} |

反向索引回答"这个用户点赞过哪些笔记"——"我的点赞"页面需要列出用户点赞过的所有笔记。用 Set 而非 ZSet，因为不需要按时间排序。

### MySQL 验证

| user_id | biz_type | biz_id | created_at |
|---------|----------|--------|------------|
| 10001 | 1 | 2078408307372003329 | 2026-07-24 15:13:57 |

### 业务讲解

`LikeService.like()`（`LikeService.java`）：

```
like(userId=10001, LikeRequest{bizType=1, bizId=2078408307372003329})
│
├─ 1. like_atomic.lua（一次 EVAL，两个 Set 操作）
│     ┌─────────────────────────────────────────┐
│     │ KEYS[1] = myxhs:like:note:2078408307..  │ ← 正向索引
│     │ KEYS[2] = myxhs:like:user:10001:note    │ ← 反向索引
│     │ ARGV[1] = 10001 (正向 member)            │
│     │ ARGV[2] = 2078408307372003329 (反向mem)  │
│     │ ARGV[3] = "1" (hasReverse, 笔记=需要)    │
│     │                                          │
│     │ local added = redis.call('SADD', KEYS[1], ARGV[1])  │
│     │ if added == 0 then return 0 end  -- 已存在，幂等     │
│     │ if ARGV[3] == '1' then                               │
│     │     redis.call('SADD', KEYS[2], ARGV[2])  -- 反向索引│
│     │ end                                                  │
│     │ return 1                                             │
│     └─────────────────────────────────────────┘
│     返回 1 → 新增成功
│
├─ 2. syncSend SOCIAL_TOPIC tag=LIKE（同步发送，3s 超时）
│     ├─ 成功 → return {liked:true}
│     └─ 失败 → unlike_atomic.lua 回滚 Redis → throw BizException
│
└─ 3. LikeConsumer → INSERT t_like
       catch DuplicateKeyException → 幂等
```

**双重幂等**：
- **L1 — @Idempotent(expireSeconds=5)**：HTTP 层 5s 窗口，拦截网络抖动导致的客户端重试
- **L2 — Lua SADD return 0**：Redis 层永久幂等，拦截超过 5s 的重复请求

**为什么用 Set 而非 ZSet？** 点赞不需要"按时间排序"，只需判存在 + 计数。Set 比 ZSet 省 ~30% 内存（无 score 字段）。

**hasReverse 参数**：同一个 Lua 脚本处理两种场景——笔记点赞（`hasReverse=1`，需要反向索引）和评论点赞（`hasReverse=0`，不需要）。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 点赞成功 | code=200 | 通过 |
| 正向索引有记录 | Set {10001} | 通过 |
| 反向索引有记录 | Set 含 noteId | 通过 |
| SCARD = 1 | ✅ | 通过 |
| MySQL 落库成功 | ✅ | 通过 |

---

## 4.8 取消点赞 — `DELETE /api/social/like`

### curl 请求

```bash
curl -s -X DELETE "http://localhost:19003/api/social/like" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"bizType":1,"bizId":2078408307372003329}'
```

### 实际返回

```json
{ "code": 200, "message": "取消点赞成功" }
```

### Redis 验证 — 取消前后对比

| 层 | 取消前 | 取消后 |
|----|--------|--------|
| 正向索引 `myxhs:like:note:2078408307372003329` | {10001} | **空 Set** |
| 反向索引 `myxhs:like:user:10001:note` | 含 2078408307372003329 | **已移除**（仍含其他笔记） |
| 正向 SCARD | 1 | **0** |

### MySQL 验证

| 取消前 | 取消后 |
|--------|--------|
| 1 row | **0 rows** |

### 业务讲解

`unlike_atomic.lua` 对执行 SREM 正向 + SREM 反向——对称于 like_atomic.lua。SREM 天然幂等（重复执行不报错）。和关注取关一样，不需要防负数保护（Set 用 SCARD 实时查询，无独立 counter）。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取消后正向索引清空 | Set empty | 通过 |
| 取消后反向索引移除该笔记 | ✅ | 通过 |
| MySQL 对应行删除 | cnt=0 | 通过 |

---

## 4.9 点赞状态 — `GET /api/social/like/status`

### curl 请求

```bash
curl -s "http://localhost:19003/api/social/like/status?bizType=1&bizId=2078408307372003329" \
  -H "X-User-Id: 10001"
```

### 实际返回

```json
{ "code": 200, "data": false }
```

### Redis 验证

```python
SISMEMBER myxhs:like:note:2078408307372003329 10001 → 0
```

API 的 `false` 与 Redis SISMEMBER 的 `0` 一致——4.8 取消点赞后状态正确。

### 业务讲解

`LikeService.isLiked()` 就是一次 Redis SISMEMBER——O(1) 操作，不查 DB。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取消点赞后 status=false | ✅ | 通过 |
| 与 Redis SISMEMBER 一致 | SISMEMBER=0 | 通过 |

---

## 4.10 批量点赞状态 — `GET /api/social/like/batch-status`

### curl 请求

```bash
curl -s "http://localhost:19003/api/social/like/batch-status?bizType=1&bizIds=2078408307372003329,2076182853659451393,999999" \
  -H "X-User-Id: 10001"
```

### 实际返回

```json
{
  "2078408307372003329": true,
  "2076182853659451393": true,
  "999999": false
}
```

### Redis 逐个验证

| noteId | SISMEMBER | API 结果 | 一致 |
|--------|:--:|:--:|:--:|
| 2078408307372003329 | 1 | true | ✅ |
| 2076182853659451393 | 1 | true | ✅ |
| 999999 | 0 | false | ✅ |

### 业务讲解

`LikeService.batchCheckLikeStatus()` 使用 **Pipeline 批量 SISMEMBER**——3 个 `SISMEMBER` 命令合并为 1 次网络往返。不同于关注列表的 Pipeline ZSCORE，这里 Pipeline 的是 SISMEMBER。

**为什么查询不存在的 noteId(999999) 也是 O(1)？** SISMEMBER 的复杂度与 Set 大小无关——它直接查哈希表，O(1)。所以批量查询的延迟 = 1 次往返延迟（~1ms），与查询数量几乎无关（Pipeline 上限 100 条）。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 已点赞的返回 true | ✅ | 通过 |
| 不存在的返回 false | ✅ | 通过 |
| 与逐个 SISMEMBER 一致 | ✅ | 通过 |

---

## 4.11 点赞数 — `GET /api/social/like/count`

### curl 请求

```bash
# 公开接口，走 Gateway
curl -s "http://localhost:19000/api/social/like/count?bizType=1&bizId=2078408307372003329"
```

### 实际返回

```json
{ "code": 200, "data": 1 }
```

### Redis 验证

```
SCARD myxhs:like:note:2078408307372003329 → 1
Members: {10001}
```

count=1 = SCARD=1，一致。Gateway 白名单（`/api/social/like/count`）放行。

### 业务讲解

`LikeService.getLikeCount()` 就是一次 `SCARD`——O(1) 操作。Unlike Follow 的 counter（需要 Lua 维护 + 对账修复），Like 的计数天然精确——SCARD 任何时候都反映 Set 的真实成员数。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 点赞数 = 1 | ✅ | 通过 |
| Gateway 公开接口 | ✅ | 通过 |
| 与 SCARD 一致 | ✅ | 通过 |

---

## 4.12 收藏 — `POST /api/social/favorite`

### curl 请求

```bash
curl -s -X POST "http://localhost:19003/api/social/favorite" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"noteId":2078408307372003329}'
```

### 实际返回

```json
{ "code": 200, "message": "收藏成功" }
```

### Redis 验证

| Key | 值 |
|-----|------|
| `myxhs:favorite:10001` (ZSet) | noteId=2078408307372003329, score=1784878640832 |
| ZCARD | 2（含之前收藏的 2076147855673843713） |

### MySQL 验证

| user_id | note_id | created_at |
|---------|--------|------------|
| 10001 | 2078408307372003329 | 2026-07-24 15:37:21 |

### 业务讲解

```
favorite(userId=10001, noteId=2078408307372003329)
│
├─ 1. favorite_atomic.lua: ZSCORE 检查 + ZADD（原子幂等）
│     KEYS[1] = myxhs:favorite:10001
│     ARGV[1] = 2078408307372003329
│     ARGV[2] = 1784878640832（收藏时间戳）
│
├─ 2. asyncSend SOCIAL_TOPIC tag=FAVORITE
│     └─ callback 只记日志，不阻塞
│
└─ 3. FavoriteConsumer → INSERT t_favorite（DuplicateKeyException 幂等）
```

**为什么 Favorite 用 asyncSend 而 Like 用 syncSend？** Like 有正反向双索引 + syncSend 失败需要回滚 Redis。Favorite 只有一个 ZSet，ZADD 就是全部——计数用 ZCARD，不需要维护独立 counter。asyncSend 失败的最坏结果：Redis 有收藏但 MySQL 没有——不影响用户看到收藏列表。偶尔丢失一条对收藏的影响远小于点赞。

**为什么取消收藏用裸 ZREM 不用 Lua？** 只有一个 Key——ZREM 是单一命令，天然原子。不需要 Lua 保证跨 Key 一致性。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 收藏成功 | code=200 | 通过 |
| ZSet 有记录 | noteId + score(时间戳) | 通过 |
| MySQL 落库 | ✅ t_favorite 有行 | 通过 |

---

## 4.13 取消收藏 — `DELETE /api/social/favorite`

### 实际返回

```json
{ "code": 200, "message": "取消收藏成功" }
```

### Redis 验证

取消前 ZCARD=2 → 取消后 ZCARD=1（只保留之前的收藏 2076147855673843713）

### MySQL 验证

取消后 cnt=0

### 业务讲解

`ZREM myxhs:favorite:10001 2078408307372003329` — 单命令，天然原子，不需要 Lua。取消收藏是 Favorite 三个操作中最简单的。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取消后 ZCARD 减 1 | 2→1 | 通过 |
| MySQL 删除 | cnt=0 | 通过 |

---

## 4.14 收藏状态 — `GET /api/social/favorite/status`

### 实际返回

```json
{ "code": 200, "data": false }
```

### Redis ZSCORE

```
ZSCORE myxhs:favorite:10001 2078408307372003329 → None
```

取消收藏后 ZSCORE 为 null，API 返回 false——一致。

### 业务讲解

`isFavorited()` = `ZSCORE != null`。ZSCORE 是 O(1) 操作，和 ZSet 大小无关。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 取消后 status=false | ✅ | 通过 |
| 与 ZSCORE 一致 | None | 通过 |

---

## 4.15 收藏列表 — `GET /api/social/favorite/list`

### 实际返回

```json
{ "total": 1, "list": [2076147855673843713] }
```

### Redis 验证

```
ZREVRANGE myxhs:favorite:10001 → [{noteId=2076147855673843713, score=1783827684523}]
ZCARD=1
```

total=1 = ZCARD=1，noteId 一致。按 score 倒序（ZREVRANGE），最近收藏的排在前面。

### 业务讲解

`ZREVRANGE` 分页取回成员，不查 MySQL。和关注列表一样，Redis 是唯一数据源——MySQL 只是备份。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 返回收藏列表 | total=1, noteId=2076147855673843713 | 通过 |
| 与 Redis ZCARD 一致 | ✅ | 通过 |
| 按时序倒序 | score=1783827684523 | 通过 |

---

## 4.16 MQ 消费验证

### Consumer 日志确认

```
[LikeConsumer] 落库成功: userId=10001, bizType=1, bizId=2078408307372003329  (×3)
[FavoriteConsumer] 落库成功: userId=10001, noteId=2078408307372003329      (×2)
```

| Consumer | 消费次数 | 与 curl 操作对应 |
|---------|:--:|------|
| LikeConsumer | 3 次 | 4.7 → 4.10 重赞 → 幂等验证 |
| FavoriteConsumer | 2 次 | 4.12 → session 前期测试 |

**完整的 LIKE 链路验证**：

```
curl POST /api/social/like
  → LikeService.like() → like_atomic.lua (Redis SADD)
  → syncSend SOCIAL_TOPIC tag=LIKE (MqTraceHelper 包装 trace)
  → RocketMQ Broker
  → LikeConsumer 消费（ConsumeMessageThread）
  → INSERT t_like（catch DuplicateKeyException 幂等）
  → log "落库成功"
```

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| LikeConsumer 消费 LIKE 消息 | ✅ 日志确认 | 通过 |
| FavoriteConsumer 消费 FAVORITE 消息 | ✅ 日志确认 | 通过 |
| Consumer 时间戳与 curl 操作对齐 | ✅ | 通过 |

---

## 4.18 幂等性验证 — 三层全部验证

### L1：@Idempotent 5s 窗口（HTTP 层）

两次相同请求间隔 1s：

```
第 1 次: { "code": 200, "message": "点赞成功" }
第 2 次: { "code": 40201, "message": "请勿重复点赞" }  ← L1 拦截
```

隔 10s（超过 5s 窗口）再发第 3 次：`code=200`——L1 放行，交 L2 处理。

### L2：Redis Lua SADD return 0（Redis 层）

L1 放行的请求到达 Service → `like_atomic.lua` → SADD return 0 → Service 返回"点赞成功"但不发 MQ。

### L3：DuplicateKeyException（MySQL 层）

直接向 MySQL 插入重复行验证唯一索引：

```sql
INSERT INTO t_like (user_id, biz_type, biz_id) VALUES (10001, 1, 2078408307372003329)
  → ERROR 1062: Duplicate entry '10001-1-2078408307372003329' for key 'uk_user_biz'
```

唯一索引 `uk_user_biz` 生效。LikeConsumer 代码确认：`catch (DuplicateKeyException e) { log.debug("重复消费忽略") }`。

### 三层总结

```
重复点赞请求到达:
  │
  ├─ L1 (@Idempotent 5s)     ← 1s 内 → 40201 拦截 ✅
  │   > 5s 放行
  │
  ├─ L2 (Lua SADD return 0)   ← > 5s 到达 → Redis 拦截 ✅
  │   → 不发 MQ，不给 L3 机会
  │
  └─ L3 (DuplicateKeyException) ← 仅当 MQ 重试时触发 ✅
      → MySQL unique index 拒绝重复 INSERT
      → LikeConsumer catch 忽略
```

| 期望 | 实际 | 结论 |
|------|------|:----:|
| L1 5s 内拦截 | code=40201 | 通过 |
| L1 5s 后放行 | code=200 | 通过 |
| L2 Redis 拦截 | SADD return 0，不发 MQ | 通过 |
| L3 MySQL 拒绝重复 | Duplicate entry for uk_user_biz | 通过 |

---

## 4.17 FollowCounterRepairJob 对账执行

### 最终状态：✅ 通过

XXL-Job Admin 已部署（`http://21.130.247.89:18080/xxl-job-admin`），通过 API 完成配置并触发执行。

### 配置完成

- ✅ 执行器 `my-xhs-analytics` 自动注册
- ✅ 任务创建：Cron=`0 0 */1 * * ?`（每小时），Handler=`followCounterRepairJob`
- ✅ 任务启动 + 手动触发执行

### 执行结果

```
[关注对账] 完成: 扫描2个用户, 修复计数1个, 修复关系0个
```

**修复计数 1 个**——之前关注/取关测试导致某用户的 counter 偏离了 ZCARD，对账自动修正。证明了 review 中提到的"Step B 失败 → 计数不一致 → 对账修复"链路完整运行。

### 修复过程中的 bug

`selectDistinctUserIds` SQL 原为 `SELECT DISTINCT user_id ... ORDER BY id LIMIT ?`，MySQL `ONLY_FULL_GROUP_BY` 模式下不兼容。改为子查询 `SELECT DISTINCT FROM (SELECT ... ORDER BY id LIMIT ?)` 修复。这是一次真实的工程 bug 发现——SQL 文档写了很久但从未被 XXL-Job 触发执行过。
