# S02 — 商品搜索 (GET /api/search/product)

> 2026-08-08 | 阶段14-1 | search服务 | testuser: mytestuser

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (/api/search/product)
         → CompletableFuture.supplyAsync (searchExecutor)
           ├── ProductSearchService.searchProducts()
           │   └── ES:19200 → product_index (关键词+高亮+SearchAfter分页)
           └── hotSearchService.recordSearchKeyword() [旁路]
               └── Redis:16381 → search:window:{minuteBucket} Lua脚本
                     (反作弊: 用户/IP频率限制 + 屏蔽词过滤)
```

## 业务逻辑

接受关键词/分类/价格区间/排序参数，通过 Elasticsearch 查询 product_index 索引，返回分页商品列表（含 Search After 游标支持深分页）。搜索结果带 `<em>` 高亮标签。同时将搜索词旁路记录到 Redis 热搜滑动窗口（分钟桶），经反作弊过滤后参与热搜计算。

## 请求参数 (ProductSearchRequest)

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| keyword | String | — | 搜索关键词 |
| categoryId | Long | — | 分类过滤 |
| minPrice | BigDecimal | — | 最低价格 |
| maxPrice | BigDecimal | — | 最高价格 |
| sort | String | relevance | 排序: relevance/price_asc/price_desc/sales |
| size | Integer | 20 | 每页大小 |
| searchAfter | String | — | 深分页游标 |

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id: 9bd8dea0ef954f23b571640b53339b5a, 5条结果, took=5ms |
| ES | ✅ | product_index total=5 条匹配, 高亮 `<em>测试</em>` 正常 |
| Redis | ~ | 热搜窗口异步记录(CompletableFuture+DEBUG日志), Lua脚本反作弊 |
| MQ | N/A | 只读端点，无MQ消费 |
| SW | ✅ | traceId已捕获，可SkyWalking UI追踪 |
| Prometheus | ⚠️ | 指标名需进一步确认 |
| Kibana | ✅ | myxhs-logs-* 命中4条日志 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -G "http://localhost:19000/api/search/product" \
  --data-urlencode "keyword=测试" \
  --data-urlencode "size=5" \
  -H "Authorization: Bearer $TOKEN"
```

## 响应摘要

```json
{"code":200,"data":{
  "items":[5 items with spuId/name/highlightName/categoryId/price/sales/createdAt],
  "total":5,"searchAfter":"[0.5105172,2081302094884671490]","hasMore":true,"took":5
}}
```

## 注意事项

1. **中文参数须URL编码**: 使用 `--data-urlencode` 而非直接拼在URL中，否则400
2. **热搜为异步旁路**: recordSearchKeyword 在 CompletableFuture 内执行，不影响搜索响应
3. **price/sales/image 为0/null**: ES索引中部分字段未完整填充（需product服务Feign调用），属已知限制
