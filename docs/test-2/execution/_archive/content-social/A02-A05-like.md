# A02-A05 — 点赞反操作 + 查询

## A02: DELETE /api/social/like — 取消点赞

### ASCII 流转图
```
[curl] → Gateway:19000 → analytics:19003
  → LikeController.unlike(X-User-Id=10001, body:LikeRequest{bizId,bizType=1})
  → LikeService.unlike()
     ├ Redis SREM myxhs:like:note:{noteId} {userId}
     └ sendCounterEvent(DECR) → RocketMQ 异步
```

### curl
```bash
curl -s -X DELETE "http://localhost:19000/api/social/like" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"bizId":2085540601761169409,"bizType":1}'
```

### 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, "取消点赞成功" | ✅ |
| Redis | SISMEMBER → False (deliked) | ✅ |
| MQ | counter Consumer DECR 异步 | ✅ |
| Prometheus | analytics actuator 指标 | ✅ |
| SkyWalking | Gateway X-Trace-Id 贯穿 | ✅ |

### 踩坑
- `bizType` 是 Integer(1=笔记,2=评论)，非 String

---

## A03: GET /api/social/like/status — 点赞状态

### ASCII 流转图
```
[curl] → Gateway:19000 → analytics:19003
  → LikeController.checkLikeStatus(X-User-Id, ?bizType=1&bizId={noteId})
     └ Redis SISMEMBER myxhs:like:note:{noteId} {userId}
```

### curl
```bash
curl -s "http://localhost:19000/api/social/like/status?bizId=2085540601761169409&bizType=1" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
# → {"code":200,"data":true}
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, data=True | ✅ |

---

## A04: GET /api/social/like/batch-status — 批量状态

### ASCII 流转图
```
[curl] → Gateway:19000 → analytics:19003
  → batchCheckLikeStatus(?bizType=1&bizIds=id1,id2)
     └ Redis Pipeline → N 次 SISMEMBER (一次网络往返)
```

### curl
```bash
curl -s "http://localhost:19000/api/social/like/batch-status?bizIds=2085540601761169409&bizType=1" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
# → {"code":200,"data":{"2085540601761169409":true}}
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, Map<bizId,Boolean> | ✅ |

---

## A05: GET /api/social/like/count — 点赞数

### curl
```bash
curl -s "http://localhost:19000/api/social/like/count?bizId=2085540601761169409&bizType=1" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
# → {"code":200,"data":1}
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, data=1 | ✅ |
| Redis | SCARD myxhs:like:note:{noteId} = 1 | ✅ |
| Prometheus | analytics actuator 指标 | ✅ |
| SkyWalking | Gateway X-Trace-Id 贯穿 | ✅ |
