# P06: 创建SKU — POST /api/product/sku

## § 源码分析

- **Controller**: `ProductController.java:143` → `@PostMapping("/sku")`, 参数 `@RequestHeader X-User-Id` + `@RequestHeader X-Admin-Call` + `@Valid @RequestBody SkuCreateRequest`
- **Service**: `SkuService.java:45` → `createSku()`
  - 校验SPU存在: `spuMapper.selectById(request.getSpuId())`, 不存在抛 `PRODUCT_NOT_FOUND`
  - 雪花ID: `idGeneratorUtil.nextId()`
  - 构建Sku实体: price/originalPrice/stock/specs/status=1(上架)
  - `skuMapper.insert(sku)` → MySQL
  - `afterCommit`: `spuService.evictSpuCache(spuId)` → 删除SPU Redis缓存(因SKU列表变更)
- **下游**: MySQL `t_sku` INSERT + Redis SPU缓存删除(`myxhs:product:spu:{spuId}` DEL)

## § 业务逻辑

管理员提交SKU(所属SPU+规格+价格) → Admin校验(X-Admin-Call) → 限流(5/min per user) → SPU存在性校验 → 生成唯一ID → insert MySQL t_sku → 事务提交后异步删除所属SPU的Redis缓存(SKU列表发生了变化) → 返回skuId

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | `X-Admin-Call: my-xhs-admin-token-2026` | 403 仅限管理员操作 |
| SPU已存在 | `mysql -P 3306 -e "SELECT id FROM my_xhs_product.t_spu WHERE id=?"` | PRODUCT_NOT_FOUND |
| 未超限流 | Redis `myxhs:product:createSku:{userId}` counter < 5 | 429 限流 |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_category LIMIT 1"` | 500 数据库连接失败 |

## § ASCII流转图

```
curl POST /api/product/sku (X-Admin-Call + X-User-Id)
  → Gateway → my-xhs-product:19006 ProductController.createSku()
    → @RateLimit Redis myxhs:product:createSku:{userId} (5/min)
    → MySQL: SELECT t_spu WHERE id=?
    → SPU不存在? → 抛 PRODUCT_NOT_FOUND
    → 雪花ID生成
    → MySQL: INSERT INTO t_sku
    → afterCommit → Redis DEL myxhs:product:spu:{spuId} (SKU列表变更)
    → 返回 {skuId}
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -i -X POST http://localhost:19000/api/product/sku -H 'Authorization: Bearer $TOKEN' -H 'X-Admin-Call: my-xhs-admin-token-2026' -H 'Content-Type: application/json' -d '{"spuId":1,"name":"红色-大码","price":29900,"originalPrice":39900,"stock":100,"specs":"红色,大码"}'` | 200, `data.skuId` > 0 |
| MySQL | `mysql -P 3306 -e "SELECT id,name,price,stock FROM my_xhs_product.t_sku WHERE id=?"` | 1行, status=1 |
| Redis SPU缓存 | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.get('myxhs:product:spu:1'))"` | 返回None(缓存已删除) |
| Redis限流 | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.get('myxhs:product:createSku:1'))"` | 1(计数器+1) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SkuService:45 单次insert | ✅ O(1) |
| 可扩展 | 雪花ID无中心依赖 | ✅ |
| 微服务 | 不依赖其他服务 | ✅ |
| 安全 | X-Admin-Call校验+@RateLimit限流 | ✅ |
| 并发 | 同一SPU并发创建SKU不冲突 | ✅ |
| 幂等 | 重复请求生成不同skuId(无去重) | ⚠️ 无幂等(业务决定) |

## § curl

```bash
# 获取TOKEN
TOKEN=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456","captcha":"dummy"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 创建SKU
curl -s -i -X POST http://localhost:19000/api/product/sku \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"spuId":1,"name":"红色-大码","price":29900,"originalPrice":39900,"stock":100,"specs":"红色,大码"}'
```
