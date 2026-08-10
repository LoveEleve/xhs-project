# FW06: 关注关系 — GET /api/social/relation/{targetUserId}

## § 源码分析

- **Controller**: `FollowController.java:113` → `@GetMapping("/relation/{targetUserId}")`, 参数 `X-User-Id` + `@PathVariable Long targetUserId`
- **Service**: 互查关注关系 → 返回 relation(0无/1已关注/2互关/3对方关注我)

## § 业务逻辑

查询当前用户与目标用户的单向/双向关注关系

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/relation/{targetUserId}"` | 200, {isFollowing, isFollowBack, isMutual} |
| Redis | `r.sismember('myxhs:following:{id}','{targetId}')` | isFollowing |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 只查Redis Set交集 | ✅ |
| 微服务 | 无Feign | ✅ |
| 并发 | 只读 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/relation/123456" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
GET /api/social/relation/{targetUserId}
  → FollowController.getRelation(currentUserId, targetUserId)
  → Redis SISMEMBER myxhs:following:{currentUserId} {targetUserId} → isFollowing
  → Redis SISMEMBER myxhs:following:{targetUserId} {currentUserId} → isFollowBack
  → isFollowing && isFollowBack → mutual
  → 返回 {isFollowing, isFollowBack, isMutual}
```


