# LK03: 点赞状态 — GET /api/social/like/status

## § 源码分析

- **Controller**: `LikeController.java:73` → `@GetMapping("/status")`, 参数 `X-User-Id` + `@RequestParam targetType` + `@RequestParam targetId`
- **Service**: `LikeService.checkLikeStatus(userId, targetType, targetId)`
  - 优先查Redis: `redisTemplate.opsForSet().isMember("like:status:{targetType}:{targetId}", userId)`
  - 未命中降级MySQL: `SELECT COUNT(id)>0 FROM t_like WHERE user_id=? AND target_type=? AND target_id=?`
  - 返回 `{liked: true | false}`
- **下游**: Redis Set + MySQL t_like

## § 业务逻辑

GET /api/social/like/status → Redis SISMEMBER快速判断 → 缓存未命中走MySQL → 返回{liked:bool} → 前端展示是否已点赞

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/like/status?targetType=NOTE&targetId=1001"` | 200, {"liked":true/false} |
| Redis | `redis-cli SISMEMBER like:status:NOTE:1001 <userId>` | 与HTTP一致 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Redis Set O(1) | ✅ |
| 降级 | Redis未命中→MySQL | ✅ |

## § curl

```bash
curl -s "http://localhost:19012/api/social/like/status?targetType=NOTE&targetId=1001" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/social/like/status?targetType=NOTE&targetId=1001
  → LikeController.checkLikeStatus(userId, targetType, targetId)
  → Redis: SISMEMBER like:status:NOTE:1001 {userId}
    → hit: return liked=true
    → miss: SELECT COUNT(*)>0 FROM t_like WHERE user_id=? AND target_type=? AND target_id=?
  → 返回 {liked: true/false}
```
