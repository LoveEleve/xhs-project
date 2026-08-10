# S10 — 置顶热搜 (PUT /api/search/hot/pin)

> 2026-08-08 | 阶段14-5 | search服务 | admin

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (PUT /api/search/hot/pin?keyword=xxx)
         → HotSearchService.pinKeyword(keyword)
           → Redis:16379 → SADD myxhs:search:hot:pinned {keyword}
```

## 业务逻辑

管理员将指定关键词加入置顶 HashSet(SADD)，热搜榜展示时置顶词排在最前面(score=0,pinned=true,tag="置顶")。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id captured |
| Redis | ✅ | SADD: "hello" 加入 pinned Set |
| 边界 | ✅ | 无X-Admin-Call/错误token→403"仅管理员可执行此操作" |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
# 置顶
curl -s -i -X PUT "http://localhost:19000/api/search/hot/pin?keyword=hello" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
# 边界: 无admin token
curl -s -i -X PUT "http://localhost:19000/api/search/hot/pin?keyword=test" \
  -H "Authorization: Bearer $TOKEN"
```

## Redis 验证

```python
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
r.smembers('myxhs:search:hot:pinned')  # 应包含 "hello"
```
