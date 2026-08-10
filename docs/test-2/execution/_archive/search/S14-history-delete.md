# S14 — 删除搜索历史 (DELETE /api/search/history/{keyword})

> 2026-08-08 | 阶段14-9 | search服务 | mytestuser(2085927845755985922)

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (DELETE /api/search/history/{keyword})
         → SearchHistoryService.deleteHistoryItem(userId, keyword)
           → Redis:16379 → 移除指定关键词的历史记录
```

## 业务逻辑

用户删除自己的单条搜索历史（Redis 存储）。支持中文关键词(URL编码传递)。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, 新用户无历史也正常返回 |
| Redis | ✅ | 幂等操作，无历史则无事发生 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X DELETE "http://localhost:19000/api/search/history/%E6%B5%8B%E8%AF%95" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085927845755985922"
```
