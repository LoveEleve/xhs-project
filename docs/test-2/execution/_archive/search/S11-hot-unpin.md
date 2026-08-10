# S11 — 取消置顶 (DELETE /api/search/hot/pin)

> 2026-08-08 | 阶段14-6 | search服务 | admin

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (DELETE /api/search/hot/pin?keyword=hello)
         → HotSearchService.unpinKeyword(keyword)
           → Redis:16379 → SREM myxhs:search:hot:pinned "hello"
```

## 业务逻辑

管理员从置顶 HashSet 移除指定关键词，该词恢复普通热度排序。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK |
| Redis | ✅ | SREM: "hello" 已从 pinned Set 移除 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X DELETE "http://localhost:19000/api/search/hot/pin?keyword=hello" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```
