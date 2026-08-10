# S12 — 屏蔽热搜 (PUT /api/search/hot/block)

> 2026-08-08 | 阶段14-7 | search服务 | admin

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (PUT /api/search/hot/block?keyword=xxx)
         → HotSearchService.blockKeyword(keyword)
           → Redis:16379 → SADD myxhs:search:hot:blocked {keyword}
           → Redis:16379 → ZREM myxhs:search:hot:realtime {keyword}
```

## 业务逻辑

管理员屏蔽关键词：加入 blocked Set(阻止后续记录) + 从 realtime ZSet 移除(立即热榜下线)。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK |
| Redis | ✅ | "testblock" 加入 blocked Set |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X PUT "http://localhost:19000/api/search/hot/block?keyword=testblock" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## Redis 验证

```python
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
r.smembers('myxhs:search:hot:blocked')  # 应包含 "testblock"
```
