# H03 — 商品详情聚合 (GET /api/home/product/{spuId})

> 2026-08-08 | 阶段14-10 | home服务 | mytestuser

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-home:19015 (GET /api/home/product/{spuId})
         → CompletableFuture.supplyAsync (aggregatorPool)
           → productAggService.getProductDetail(spuId)
             ├── Feign: product服务 → t_spu + t_sku
             ├── Feign: inventory服务 → stock查询
             ├── Feign: counter服务 → collectCount/viewCount
             └── 组装 ProductDetailAggVO
```

## 业务逻辑

聚合 home 首页商品详情页：SPU 基本信息 + SKU 列表(价格/规格/库存) + 分类名称 + 商品计数(收藏/浏览) + 相关笔记。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id captured |
| 数据 | ✅ | spuId=2085352027803664385, name="P01测试商品", categoryName="服饰" |
| SKU | ✅ | skuId=2085530413171785729, price=29.90, availableStock=983 |
| Redis | ✅ | inventory stock 983 (Feign调用inventory服务) |
| MySQL | ✅ | t_spu + t_sku 数据正确 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i "http://localhost:19000/api/home/product/2085352027803664385" \
  -H "Authorization: Bearer $TOKEN"
```

## 响应

```json
{"code":200,"data":{
  "spuId":2085352027803664385,"name":"P01测试商品","categoryName":"服饰",
  "skuList":[{"skuId":2085530413171785729,"price":29.90,"availableStock":983}],
  "collectCount":0,"viewCount":0,"relatedNotes":[]
}}
```
