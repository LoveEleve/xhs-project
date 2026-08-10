# FA04: 收藏列表 — GET /api/social/favorite/list

## § 源码分析

- **Controller**: `FavoriteController.java:81` → `@GetMapping("/list")`, 参数 `X-User-Id` + `page/1` + `size/20`
- **Service**: `FavoriteService.getFavoriteList(userId, page, size)` → ZSet ZREVRANGE 倒序取noteId
  - `FavoriteService.getFavoriteCount(userId)` → ZCARD
- **下游**: Redis ZSet

## § 业务逻辑

ZREVRANGE按收藏时间倒序分页 → ZCARD获取总数 → 返回{total, list[noteId]}

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/social/favorite/list?page=1&size=20` | 200, {total, list} |
| Redis | `r.zcard('myxhs:favorite:{userId}')` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | ZREVRANGE分页 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/favorite/list?page=1&size=20" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -10
```

## § ASCII流转图

```
GET /api/social/favorite/list?page=1&size=20
  → FavoriteController.getFavoriteList(userId, page, size)
  → ZREVRANGE myxhs:favorite:{userId} offset limit → noteId列表
  → ZCARD myxhs:favorite:{userId} → total
  → 返回 {total: N, list: [noteIds]}
```
