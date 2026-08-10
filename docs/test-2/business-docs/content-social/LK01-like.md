# LK01: 点赞 — POST /api/social/like

## § 源码分析

- **Controller**: `LikeController.java:42` → `@PostMapping`, 参数 `X-User-Id` + `@RequestBody LikeDTO{targetType: NOTE|COMMENT, targetId}`
- **Service**: `LikeService.like(userId, targetType, targetId)`
  - 幂等检查: Redis SETNX `like:idempotent:{userId}:{targetType}:{targetId}` TTL 5s
  - `INSERT IGNORE INTO t_like (user_id, target_type, target_id) VALUES (?, ?, ?)`
  - `RocketMQTemplate.send("SOCIAL_TOPIC", {event: LIKE_NOTE | LIKE_COMMENT, userId, targetId})`
  - CounterEventConsumer → `UPDATE t_note SET like_count=like_count+1` 或 `UPDATE t_comment SET like_count=like_count+1`
- **锁**: `@RateLimit(key="like:create", rate=30, window=60)` 每分钟30次
- **幂等**: `@Idempotent(key="like:{userId}:{targetType}:{targetId}", ttl=5)` 5秒窗口防重复
- **下游**: MySQL t_like + Redis MQ EVENT_TOPIC → CounterEventConsumer

## § 业务逻辑

POST /api/social/like → 目标类型(NOTE/Comment) → Redis幂等去重 → MySQL INSERT IGNORE → MQ异步计数+1 → 返回ok

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| RateLimit | 30次/分钟 | 429 Too Many Requests |
| @Idempotent | 5秒内同target | 幂等返回ok不报错 |
| targetType有效 | NOTE 或 COMMENT | 400 bad request |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/social/like` | 200 |
| MySQL | `SELECT COUNT(*) FROM t_like WHERE user_id=? AND target_type='NOTE' AND target_id=?` | 1 |
| Redis | `redis-cli GET like:idempotent:{userId}:NOTE:{targetId}` | key存在(5s TTL) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id过滤 | ✅ |
| 幂等 | Redis锁5s + INSERT IGNORE | ✅ |
| 速率 | @RateLimit 30/min | ✅ |
| MQ | SOCIAL_TOPIC投递成功 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19012/api/social/like \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"targetType":"NOTE","targetId":1001}'
```

## § ASCII流转图

```
POST /api/social/like {targetType:"NOTE", targetId:1001}
  → LikeController.like(userId, dto)
  → @Idempotent: SETNX like:idempotent:{userId}:NOTE:1001 TTL 5s
    → already exists: 直接return ok (幂等)
  → INSERT IGNORE t_like(user_id, target_type, target_id)
  → MQ SOCIAL_TOPIC → CounterEventConsumer → UPDATE t_note SET like_count+1
  → 返回 ok
```
