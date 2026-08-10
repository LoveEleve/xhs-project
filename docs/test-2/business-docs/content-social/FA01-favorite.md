# FA01: 收藏笔记 — POST /api/social/favorite

## § 源码分析

- **Controller**: `FavoriteController.java:38` → `@PostMapping`, 参数 `X-User-Id` + `@Valid @RequestBody FavoriteRequest{noteId}`
- **Service**: `FavoriteService.favorite(userId, noteId)`
  - Redis ZSet: ZSCORE 判断幂等(重复收藏直接返回)
  - `ZADD myxhs:favorite:{userId} System.currentTimeMillis() noteId`
  - MQ: asyncSend SOCIAL_TOPIC → CounterEventConsumer 异步更新计数
  - `@Idempotent(key="favorite:{userId}:{noteId}", expireSeconds=5)` 防网络抖动重复提交
- **下游**: Redis ZSet + MQ SOCIAL_TOPIC

## § 业务逻辑

ZSCORE幂等检查 → ZADD(时间戳score按收藏时间排序) → MQ异步事件SOCIAL_TOPIC → 计数器+1 → 返回收藏成功

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| 幂等拦截 | `@Idempotent` 5秒内重复 | 返回相同结果 |
| RateLimit | 30次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/social/favorite -d '{"noteId":123}'` | 200 |
| Redis | `r.zscore('myxhs:favorite:{userId}', noteId)` | 时间戳 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 幂等 | ZSCORE + @Idempotent | ✅ |
| 微服务 | MQ异步计数器 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId":2085989641275572226}'
```

## § ASCII流转图

```
POST /api/social/favorite + {noteId}
  → FavoriteController.favorite(userId, request)
    → @Idempotent 5秒幂等拦截
    → ZSCORE myxhs:favorite:{userId} noteId → 已存在返回成功
    → ZADD myxhs:favorite:{userId} time noteId
    → MQ SOCIAL_TOPIC → CounterEventConsumer +1
    → 返回 ok
```
