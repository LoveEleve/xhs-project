# P07: SKU详情 — GET /api/product/sku/{skuId}

## § 源码分析

- **Controller**: `ProductController.java:159` → `@GetMapping("/sku/{skuId}")`
- **Service**: `SkuService.java:80` → `getSkuDetail()`
  - `skuMapper.selectById(skuId)` 直查MySQL t_sku
  - SKU不存在 → 抛 `BizException(ResultCode.SKU_NOT_FOUND)`
  - 转换 `toSkuVO(sku)`: id/spuId/name/price/originalPrice/specs(注意: stock字段已从SkuVO剔除, 真实库存以inventory服务为准)
- **下游**: MySQL `t_sku` SELECT by primary key, 无Redis缓存(SKU随SPU缓存一起管理)

## § 业务逻辑

通过SKU ID查询SKU详情 → 直查MySQL t_sku表 → PK查询(id索引) → 存在返回VO(id/spuId/name/price/originalPrice/specs) → 不存在返回SKU_NOT_FOUND。SKU无独立缓存——单个SKU查询流量低, 无需缓存; 当随SPU一起查询时走SPU的Redis逻辑过期缓存。

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| SKU已创建(P06) | `mysql -P 3306 -e "SELECT id FROM my_xhs_product.t_sku WHERE id=?"` | SKU_NOT_FOUND |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_sku LIMIT 1"` | 500 |

## § ASCII流转图

```
curl GET /api/product/sku/{skuId}
  → Gateway → my-xhs-product:19006 ProductController.getSkuDetail()
    → 无Redis缓存(直查MySQL)
    → MySQL: SELECT * FROM t_sku WHERE id=?
    → 不存在? → SKU_NOT_FOUND
    → toSkuVO转换(剔除stock字段)
    → 返回 SkuVO
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s http://localhost:19000/api/product/sku/{skuId}` | 200, `data.id`, `data.name`, `data.price`, `data.spuId` |
| HTTP 不存在 | `curl -s http://localhost:19000/api/product/sku/99999` | 未找到SKU |
| MySQL | `mysql -P 3306 -e "SELECT id,name,price,spu_id FROM my_xhs_product.t_sku WHERE id={skuId}"` | 1行 |
| Redis | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.keys('*sku*'))"` | 空(SKU无独立缓存) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | PK索引O(1) | ✅ |
| 安全 | 公开接口, 无认证 | ✅ |
| 微服务 | 不依赖其他服务 | ✅ |
| 可扩展 | stock字段预留但值以inventory为准 | ✅ |

## § curl

```bash
# 查已有SKU (假设skuId=1已通过P06创建)
curl -s http://localhost:19000/api/product/sku/1 | python3 -m json.tool
# 预期: { "code": 200, "data": { "id": 1, "spuId": 1, "name": "红色-大码", "price": 29900, ... } }

# 查不存在的SKU
curl -s http://localhost:19000/api/product/sku/99999 | python3 -m json.tool
# 预期: { "code": ..., "message": "未找到SKU" }
```
