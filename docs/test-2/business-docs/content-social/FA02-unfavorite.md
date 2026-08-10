# FA02: 取消收藏 — DELETE /api/social/favorite

## § 源码分析

- **Controller**: `FavoriteController.java:53` → `@DeleteMapping`, 参数 `X-User-Id` + `@Valid @RequestBody FavoriteRequest{noteId}`
- **Service**: `FavoriteService.unfavorite(userId, noteId)`
  - Redis ZSet: `ZREM myxhs:favorite:{userId} noteId`
  - MQ: asyncSend SOCIAL_TOPIC → CounterEventConsumer 异步更新计数-1
- **下游**: Redis ZSet + MQ SOCIAL_TOPIC

## § 业务逻辑

ZREM删除收藏记录 → MQ异步事件SOCIAL_TOPIC → 计数器-1 → 返回取消成功

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| RateLimit | 30次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/social/favorite -d '{"noteId":123}'` | 200 |
| Redis | `r.zscore('myxhs:favorite:{userId}', noteId)` | null |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | MQ异步计数器-1 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId":2085989641275572226}'
```

## § ASCII流转图

```
DELETE /api/social/favorite + {noteId}
  → FavoriteController.unfavorite(userId, request)
    → ZREM myxhs:favorite:{userId} noteId
    → MQ SOCIAL_TOPIC → CounterEventConsumer -1
    → 返回 ok
```
