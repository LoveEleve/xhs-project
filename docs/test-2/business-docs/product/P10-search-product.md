# P10: 产品搜索 — GET /api/search/product

## § 源码分析

- **Controller**: `SearchController.java:68` → `@GetMapping("/product")`, my-xhs-search服务(端口19016)
- **Service**: `ProductSearchService.java:61` → `searchProducts()`
  - ES索引: `product_index` (`${search.product.index-name}`)
  - 查询构建 `buildProductQuery()`:
    - must: 关键词 match `name` 字段, analyzer=`ik_smart` (中文分词)
    - 无关键词 → `matchAll`
    - filter: `status=1` (只搜上架商品) + `categoryId` (可选) + `price` range (可选)
  - 排序 `applyProductSorting()`: relevance(默认)/price_asc/price_desc/sales
  - 分页: Search After(支持深度翻页)
  - 高亮: name字段 `<em>...</em>`
  - 指标: `businessMetrics.recordFeedPush("product_search")` + `recordOrderCreateLatency(tookMs)`
  - 异常: ES查询失败 → 返回空列表(不报500, 业务降级)
- **下游**: ES `product_index` + 热搜写 Redis ZSet `SEARCH_HOT_REALTIME`

## § 业务逻辑

用户搜索商品 → keyword ik_smart中文分词 → ES product_index查询(match name) → filter仅上架商品(status=1) → 可选分类/价格过滤 → Search After分页 → name高亮(<em>高亮</em>) → 排序(relevance/price/sales) → 记录搜索次数到metrics → 异步写Redis ZSet热搜(后台batch import) → 返回SearchResultVO(items+total+hasMore+took)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| search服务 Nacos注册 | `curl http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-search` | Gateway 503 |
| ES product_index存在 | `curl -s http://21.130.247.89:9200/product_index/_stats/docs` | 搜索结果为空(业务降级) |
| ES有数据 | `curl -s -X POST 'http://21.130.247.89:9200/product_index/_search' -H 'Content-Type: application/json' -d '{"query":{"match_all":{}},"size":1}'` | 无结果(非错误) |
| Canal→ES同步正常 | 先P01创建SPU → 等2-3s → 再P10搜索该SPU | 新商品搜不到(同步延迟) |

## § ASCII流转图

```
curl GET /api/search/product?keyword=连衣裙&sort=price_asc&size=20
  → Gateway → my-xhs-search:19016 SearchController.searchProducts()
    → CompletableFuture异步执行
    → ProductSearchService.searchProducts()
      → ES product_index:
        → query: bool(must: match "name" ik_smart, filter: status=1, categoryId, price range)
        → sort: relevance/price_asc/price_desc/sales
        → Search After: 深翻页
        → highlight: name <em>高亮</em>
      → ES成功: buildProductResult(hits→VO, 高亮字段填充)
      → ES失败: catch → 返回空列表(不抛500)
    → Metrics: recordFeedPush("product_search")
    → 热搜: 异步batch import到 Redis ZSet SEARCH_HOT_REALTIME
    → 返回 SearchResultVO<ProductSearchVO>
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s "http://localhost:19000/api/search/product?keyword=测试&size=10"` | 200, `data.items[]` 含高亮 |
| HTTP(无keyword) | `curl -s "http://localhost:19000/api/search/product?size=5"` | 200, matchAll返回全部上架商品 |
| HTTP(排序) | `curl -s "http://localhost:19000/api/search/product?keyword=测试&sort=price_asc&size=5"` | 价格升序 |
| HTTP(价格过滤) | `curl -s "http://localhost:19000/api/search/product?keyword=测试&minPrice=10000&maxPrice=50000"` | items价格在范围内 |
| ES直接查 | `curl -s -X POST 'http://21.130.247.89:9200/product_index/_search' -H 'Content-Type: application/json' -d '{"query":{"match":{"name":"测试"}},"size":3}'` | hits>0 |
| ES索引统计 | `curl -s 'http://21.130.247.89:9200/product_index/_stats/docs' \| python3 -c "import sys,json; print(json.load(sys.stdin).get('_all',{}).get('primaries',{}).get('docs',{}).get('count','N/A'))"` | N≥1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Search After支持深度翻页 + CompletableFuture异步 | ✅ |
| 可扩展 | 多维度filter+sort可组合 | ✅ |
| 安全 | 公开搜索接口, 无认证 | ✅ |
| 弹性 | ES异常返回空列表非500(业务降级) | ✅ |
| 微服务 | search服务独立, 不依赖product服务 | ✅ |
| 数据一致性 | Canal→MQ→ES增量+IndexRebuildJob凌晨全量 | ✅ |
| 可用性 | ES不可用不影响SPU/SKU增删改查 | ✅ |

## § curl

```bash
# 基础搜索
curl -s "http://localhost:19000/api/search/product?keyword=连衣裙&size=10" | python3 -m json.tool
# 预期: { "code": 200, "data": { "items": [ { "id": ..., "name": "...", "highlightName": "<em>连衣裙</em>", "price": ... } ], "total": N, "hasMore": ..., "took": ... } }

# 无关键词(搜索全部)
curl -s "http://localhost:19000/api/search/product?size=5" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(f'总{len(d[\"items\"])}条, total={d[\"total\"]}, took={d[\"took\"]}ms')"

# 价格过滤+排序
curl -s "http://localhost:19000/api/search/product?keyword=卫衣&minPrice=10000&maxPrice=50000&sort=price_asc&size=5" | python3 -m json.tool

# 分类过滤
curl -s "http://localhost:19000/api/search/product?keyword=手机&categoryId=1&size=5" | python3 -m json.tool
```
