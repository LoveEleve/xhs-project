# FW01: 关注 — POST /api/social/follow/{targetUserId}

## § 源码分析

- **Controller**: `FollowController.java:36` → `@PostMapping("/follow/{targetUserId}")`, 参数 `X-User-Id` + `@PathVariable Long targetUserId`
- **Service**: `FollowService.follow(userId, targetUserId)`
  - Lua 原子: SADD following + SADD follower + 更新计数
    - Redis: `myxhs:following:{userId}` (Set, 关注列表)
    - Redis: `myxhs:follower:{targetUserId}` (Set, 粉丝列表)
    - Redis: `myxhs:following:count:{userId}` + `myxhs:follower:count:{targetUserId}`(计数器)
  - MQ: asyncSend SOCIAL_TOPIC → CounterEventConsumer
- **下游**: Redis Sets + Counters + MQ SOCIAL_TOPIC

## § 业务逻辑

Lua原子保证关注/计数一致性 → Redis SADD双向关系 → 计数器INCR → MQ异步事件

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| 不能关注自己 | targetUserId != userId | 逻辑错误 |
| RateLimit | 20次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/social/follow/123` | 200 |
| Redis | `r.sismember('myxhs:following:{userId}','123')` | 1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 原子 | Lua脚本保证 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/social/follow/2085982901507301379 \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/social/follow/{targetUserId}
  → FollowController.follow(userId, targetUserId)
  → Lua: SADD myxhs:following:{userId} targetUserId
  → Lua: SADD myxhs:follower:{targetUserId} userId
  → Lua: INCR following:count{userId} + INCR follower:count{targetUserId}
  → MQ SOCIAL_TOPIC
```
