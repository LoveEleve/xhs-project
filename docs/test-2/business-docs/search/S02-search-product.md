# S02: 商品搜索 — GET /api/search/product

## § 源码分析
- **Controller**: `SearchController.java:68` → keyword, categoryId, minPrice, maxPrice, sort, searchAfter
- **Service**: `ProductSearchService.searchProducts()` → ES product_index match name + filter status=1, categoryId, price range + sort(composite/price/sales) + searchAfter分页 + highlight
- **下游**: ES product_index + Redis `myxhs:search:suggest:cache:{md5}`

## § 业务逻辑
商品搜索 → ES product_index match name(ik_smart) → filter status=1(在售) + categoryId + price gte/lte → sort综合/价格/销量 → searchAfter分页(10条/页) → 高亮 → 异步记录热搜

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| ES可用 | `curl "localhost:9200/_cat/health"` | 返回空 |
| Nacos注册 | Gateway路由可达 | 503 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "http://localhost:19000/api/search/product?keyword=SPU001&categoryId=1"` | 200, productSearchVOList |
| ES | `curl "localhost:9200/product_index/_search" -d '{"query":{"bool":{"must":[{"match":{"name":"SPU001"}}],"filter":[{"term":{"status":1}}]}}}'` | hits |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | ES filter bool缓存加速 | ✅ |
| 可扩展 | searchAfter防深度翻页 | ✅ |

## § curl
```bash
curl -s "http://localhost:19000/api/search/product?keyword=SPU001&categoryId=1&minPrice=0&maxPrice=99999&sort=composite&size=10"
```

## § ASCII流转图
```
GET /search/product?keyword=XX&categoryId=1&price=0-999
  → ES product_index bool query(must match name + filter term(status=1,categoryId,range price))
  → sort composite/price/sales → searchAfter
  → highlight name → 异步 S09 record keyword
  → 返回 productSearchVOList
```
