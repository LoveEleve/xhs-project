# S03 — 搜索建议

`GET /api/search/suggest?prefix={prefix}`

## ASCII 流转图

```
[curl] → Gateway:19000 → search:19016
  → SearchController.suggest(?prefix=测试)
  → SuggestService.suggest()
     ├ Redis GET myxhs:search:suggest:{MD5(prefix)} → Cache-Aside
     │   hit: 直接返回 JSON.parseArray
     ├ miss: ES Completion Suggester → note_index
     └ Redis SET (TTL: empty=5min / non-empty=config)
```

## 业务逻辑

搜索建议采用 Cache-Aside 模式：先查 Redis 缓存（key=MD5 hash 防特殊字符），缓存命中直接返回；未命中查 ES Completion Suggester，结果写回 Redis。空结果缓存 5 分钟防穿透，非空结果用配置 TTL。

## curl

```bash
# 中文必须 URL 编码
curl -s "http://localhost:19000/api/search/suggest?prefix=%E6%B5%8B%E8%AF%95" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"

# ASCII 可直接用
curl -s "http://localhost:19000/api/search/suggest?prefix=test" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

## 七层验证

| 层 | 验证方法 | 实际 | 状态 |
|------|------|------|:--:|
| HTTP | curl (URL encoded) | 200, data=[] | ✅ |
| Redis | `GET myxhs:search:suggest:{MD5}` | 缓存写入 | ✅ |
| ES | `POST note_index/_search` Completion Suggester | 0 options（索引无匹配数据） | ⚠️ |
| MQ | N/A | — | — |
| Prometheus | `/actuator/prometheus` | 指标曝光 | ✅ |
| SkyWalking | Gateway `X-Trace-Id` | traceId 可追溯 | ✅ |
| 日志 | search access log | 200, 517ms [SLOW] | ✅ |

## 踩坑

- **中文参数 400**: curl 传中文 `prefix=测试` 返回 400，需 URL 编码 `prefix=%E6%B5%8B%E8%AF%95`
- **空结果**: ES suggest 无匹配——需重建索引 + Canal 同步数据
