# S13 — 取消屏蔽 (DELETE /api/search/hot/block)

> 2026-08-08 | 阶段14-8 | search服务 | admin

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (DELETE /api/search/hot/block?keyword=testblock)
         → HotSearchService.unblockKeyword(keyword)
           → Redis:16379 → SREM myxhs:search:hot:blocked "testblock"
```

## 业务逻辑

管理员取消屏蔽，关键词可再次被记录到热搜窗口。注意：Realtime ZSet 中该词需通过自然搜索重新积累。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK |
| Redis | ✅ | "testblock" 已从 blocked Set 移除 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X DELETE "http://localhost:19000/api/search/hot/block?keyword=testblock" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```
