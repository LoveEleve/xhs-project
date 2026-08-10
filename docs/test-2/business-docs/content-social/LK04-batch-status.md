# LK04: 批量点赞状态 — GET /api/social/like/batch-status

## § 源码分析

- **Controller**: `LikeController.java:90` → `@GetMapping("/batch-status")`, 参数 `X-User-Id` + `@RequestParam int bizType` + `@RequestParam String bizIds(逗号分隔)`
- **Service**: `LikeService.batchCheckLikeStatus(userId, bizType, idList)`
  - Pipeline: 一次RTT批量执行 N次 SISMEMBER
  - 返回 `Map<Long, Boolean>`
- **下游**: Redis Pipeline

## § 业务逻辑

逗号分隔解析bizIds → Pipeline批量SISMEMBER → 返回每个id的点赞状态(列表页一次性批量查询优化)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/like/batch-status?bizType=1&bizIds=123,456"` | 200, {123:true,456:false} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Pipeline一次RTT | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/like/batch-status?bizType=1&bizIds=2085989641275572226,2085989641275572227" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/social/like/batch-status?bizType=1&bizIds=123,456
  → 解析bizIds → Pipeline SISMEMBER myxhs:like:1:{id} userId × N
  → 返回 Map<id, Boolean>
```
