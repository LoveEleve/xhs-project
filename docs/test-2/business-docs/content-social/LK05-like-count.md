# LK05: 点赞数 — GET /api/social/like/count

## § 源码分析

- **Controller**: `LikeController.java:110` → `@GetMapping("/count")`, 参数 `@RequestParam int bizType` + `@RequestParam Long bizId`, 无需登录
- **Service**: `LikeService.getLikeCount(bizType, bizId)` → `SCARD myxhs:like:{bizType}:{bizId}`

## § 业务逻辑

SCARD获取Set大小即点赞人数(公开接口，无需登录)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 无需登录 | 公开接口 | 无需 |
| bizType有效 | 1(笔记)或2(评论) | 返回0 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/like/count?bizType=1&bizId=123"` | 200, count |
| Redis | `r.scard('myxhs:like:1:123')` | = count |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SCARD O(1) | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/like/count?bizType=1&bizId=2085989641275572226" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/social/like/count?bizType=1&bizId={noteId}
  → SCARD myxhs:like:1:{noteId} → count
```
