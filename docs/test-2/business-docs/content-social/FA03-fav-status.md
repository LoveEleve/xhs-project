# FA03: 收藏状态 — GET /api/social/favorite/status

## § 源码分析

- **Controller**: `FavoriteController.java:68` → `@GetMapping("/status")`, 参数 `X-User-Id` + `@RequestParam Long noteId`
- **Service**: `FavoriteService.isFavorited(userId, noteId)`
  - Redis ZSet: `ZSCORE myxhs:favorite:{userId} noteId` → null=false / value=true

## § 业务逻辑

ZSCORE检查noteId是否在收藏ZSet中 → 返回Boolean

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/favorite/status?noteId=123"` | 200, true/false |
| Redis | `r.zscore('myxhs:favorite:{userId}','123')` | != null → true |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | ZSCORE O(1) | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/favorite/status?noteId=2085989641275572226" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
GET /api/social/favorite/status?noteId={id}
  → FavoriteController.checkFavoriteStatus(userId, noteId)
  → ZSCORE myxhs:favorite:{userId} {noteId}
  → null → false, not null → true
```
