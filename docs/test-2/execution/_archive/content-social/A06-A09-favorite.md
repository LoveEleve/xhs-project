# A06-A09 — 收藏 CRUD 全链路

> 深度验证完成（2026-08-07）：全链路通。Redis key 格式 `myxhs:favorite:{userId}`。

## A06: POST /api/social/favorite — 收藏

### ASCII 流转图
```
[curl] → Gateway:19000 → analytics:19003
  → FavoriteController.favorite(X-User-Id=10001, body:FavoriteRequest{noteId})
  → FavoriteService.favorite()
     ├ Lua ZSCORE check → ZADD myxhs:favorite:{userId} {noteId} {timestamp}
     │   幂等: ZSCORE 已存在 → return 0, skip
     ├ sendFavoriteEvent(FAVORITE) → RocketMQ 异步
     │   └ MQ 失败 → ZREM 回滚 + BizException
     └ log "收藏成功"
```

### curl
```bash
curl -s -X POST "http://localhost:19000/api/social/favorite" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"noteId":2085540601761169409}'
```

### 深度七层验证

| 层 | 验证方法 | 实际 | 状态 |
|------|------|------|:--:|
| HTTP | curl | 200, "收藏成功" | ✅ |
| Redis ZSCORE | `python3 redis.zscore('myxhs:favorite:10001','2085540601761169409')` | ZSCORE=1786092989052 | ✅ |
| Redis ZCARD | `python3 redis.zcard('myxhs:favorite:10001')` | 3 items | ✅ |
| A08 status | GET /favorite/status | 200, data=True | ✅ |
| A09 list | GET /favorite/list | 200, 3 items | ✅ |
| Prometheus | `actuator/prometheus \| grep favorite` | 指标已曝光 | ✅ |
| SkyWalking | Gateway X-Trace-Id | traceId 捕获 | ✅ |
| ES/Kibana | `myxhs-logs-* \| grep favorite` | 1 条日志 | ✅ |

### 误报说明

初期用错 Redis key 格式（`myxhs:favorite:user:10001` vs 正确 `myxhs:favorite:10001`），导致误判 ZADD 写入失败。源码 `RedisKeyConstants.FAVORITE_SET = PROJECT_PREFIX + "favorite:"` → key 应为 `myxhs:favorite:{userId}`。

---

## A07: DELETE /api/social/favorite — 取消收藏

### curl
```bash
curl -s -X DELETE "http://localhost:19000/api/social/favorite" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"noteId":2085540601761169409}'
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200 | ✅ |
| Redis ZSCORE | None (ZREM 确认) | ✅ |

---

## A08: GET /api/social/favorite/status — 收藏状态

```bash
curl -s "http://localhost:19000/api/social/favorite/status?noteId=2085540601761169409" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| 200, data=True/False | 与 Redis ZSCORE 一致 | ✅ |

---

## A09: GET /api/social/favorite/list — 收藏列表

```bash
curl -s "http://localhost:19000/api/social/favorite/list?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| 200, 3 items | noteId 列表正确 | ✅ |
