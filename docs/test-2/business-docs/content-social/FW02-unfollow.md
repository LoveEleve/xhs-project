# FW02: 取关 — DELETE /api/social/follow/{targetUserId}

## § 源码分析

- **Controller**: `FollowController.java:52` → `@DeleteMapping("/follow/{targetUserId}")`, 参数 `X-User-Id` + `@PathVariable Long targetUserId`
- **Service**: `FollowService.unfollow(userId, targetUserId)`
  - Lua 原子: SREM following + SREM follower + DECR计数
  - MQ: SOCIAL_TOPIC

## § 业务逻辑

Lua原子删除双向关系 → 计数器DECR → MQ异步事件

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| RateLimit | 20次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/social/follow/123` | 200 |
| Redis | `r.sismember('myxhs:following:{userId}','123')` | 0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 原子 | Lua脚本保证 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19000/api/social/follow/2085982901507301379 \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
DELETE /api/social/follow/{targetUserId}
  → Lua: SREM myxhs:following:{userId} targetUserId
  → Lua: SREM myxhs:follower:{targetUserId} userId
  → Lua: DECR both counters
  → MQ SOCIAL_TOPIC
```
