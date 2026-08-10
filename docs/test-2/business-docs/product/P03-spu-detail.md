# P03: SPU详情 — GET /api/product/spu/{spuId}

## § 源码分析

- **Controller**: `ProductController.java:92` → `@GetMapping("/spu/{spuId}")`, `getSpuDetail()`
- **Service**: `SpuService.getSpuDetail()` — 三级缓存链:
  - 1. 布隆过滤器 `mightContain(spuId)` → false → `PRODUCT_NOT_FOUND`
  - 2. Redis `myxhs:product:spu:{spuId}` → 命中且未过期: 直接返回
  - 3. 逻辑过期30min: 返回旧值 + 异步重建(`SPU_ASYNC_EXECUTOR`线程池) → Redisson互斥锁 `myxhs:product:lock:spu:{spuId}` → MySQL查t_spu+t_sku+t_category → 回写Redis 30min → 解锁
  - 4. 未命中: 加锁 → MySQL → 回写Redis → 解锁
- **缓存策略**: Redis Hash逻辑过期(CacheAside变种) + 布隆过滤器防缓存穿透
- **下游**: Redis Bloom + Redis Hash 30min + MySQL t_spu/t_sku/t_category

## § 业务逻辑

用户请求SPU详情(GET /spu/{spuId}) → 布隆过滤器判"可能存在" → 不存在直接404 → Redis缓存查 → 命中未过期返回 → 命中但逻辑过期(30min): 返回旧数据+后台线程异步重建(加分布式锁互斥) → 未命中: 加锁→MySQL查询SPU+SKU+分类→序列化写Redis(30min)→解锁返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| SPU已创建(P01) | `mysql -P 3306 -e "SELECT id,name FROM my_xhs_product.t_spu WHERE status=1 LIMIT 1"` | PRODUCT_NOT_FOUND |
| Redis可连 | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); r.ping()"` | 降级直查MySQL(无缓存保护) |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_spu LIMIT 1"` | 500 |

## § ASCII流转图

```
curl GET /api/product/spu/{spuId}
  → Gateway → my-xhs-product:19006 ProductController.getSpuDetail()
    → SpuService.getSpuDetail(spuId)
      → 1. Redis Bloom mightContain(spuId)?
          → NO → PRODUCT_NOT_FOUND (404)
          → YES → 进入L2
      → 2. Redis GET myxhs:product:spu:{spuId}
          → 命中 + 未过期(30min内) → 直接返回
          → 命中 + 逻辑过期(>30min) → 返回旧值 + 异步重建
              → Redisson lock myxhs:product:lock:spu:{spuId}
              → MySQL: SELECT t_spu + JOIN t_sku + t_category
              → Redis SET myxhs:product:spu:{spuId} JSON, TTL=1800
              → Redisson unlock
          → 未命中 → 加锁 → MySQL查 → 回写Redis → 解锁返回
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s http://localhost:19000/api/product/spu/{spuId}` | 200, `data.name`, `data.status`, `data.skus[]` |
| HTTP 不存在 | `curl -s http://localhost:19000/api/product/spu/99999` | PRODUCT_NOT_FOUND |
| Redis Bloom | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.execute_command('BF.EXISTS','myxhs:product:bloom:spu',99999))"` | 0 |
| Redis Cache | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); v=r.get('myxhs:product:spu:{spuId}'); print('HIT' if v else 'MISS')"` | HIT(首次后) |
| Redis TTL | `python3 -c "...; print(r.ttl('myxhs:product:spu:{spuId}'))"` | 0~1800 |
| MySQL | `mysql -P 3306 -e "SELECT id,name,status FROM my_xhs_product.t_spu WHERE id={spuId}"` | 1行 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 布隆→Redis→MySQL三级缓存, 95%命中Redis | ✅ |
| 可扩展 | 逻辑过期+互斥锁, 热点数据异步单线程重建 | ✅ |
| 安全 | 公开读接口, 无认证 | ✅ |
| 并发 | Redisson分布式锁防缓存击穿(缓存失效瞬间大量并发查DB) | ✅ |
| 弹性 | 布隆过滤器防穿透(大量不存在ID打挂DB) | ✅ |
| 微服务 | 不依赖其他服务 | ✅ |

## § curl

```bash
# 查询已有SPU (假设spuId=1)
curl -s http://localhost:19000/api/product/spu/1 | python3 -m json.tool
# 预期: { "code": 200, "data": { "id": 1, "name": "测试商品", "status": 1, "skus": [...] } }

# 查询不存在的SPU
curl -s http://localhost:19000/api/product/spu/99999 | python3 -m json.tool
# 预期: { "code": ..., "message": "PRODUCT_NOT_FOUND" }
```
