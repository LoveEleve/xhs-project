# P01: 创建SPU — POST /api/product/spu

## § 源码分析

- **Controller**: `ProductController.java:57` → `@PostMapping("/spu")`, 参数 `@RequestHeader X-User-Id` + `X-Admin-Call` + `@Valid @RequestBody SpuCreateRequest`
- **Service**: `SpuService.java:244` → `createSpu()`
  - 校验分类存在(`categoryMapper.selectById`)
  - 雪花ID生成(`idGeneratorUtil.nextId()`)
  - 构建Spu实体(status默认1上架)
  - `spuMapper.insert(spu)` → MySQL
  - `afterCommit` 注册布隆过滤器(`spuBloomFilter.add`)
- **下游**: MySQL t_spu INSERT + 布隆过滤器更新

## § 业务逻辑

提交SPU名称+分类ID等 → 管理员校验(X-Admin-Call) → 限流(5/min) → 分类存在性校验 → 生成唯一ID → insert MySQL → 事务提交后注册布隆过滤器(回滚不注册避免假阳性) → 返回spuId

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 管理员Token | `X-Admin-Call: my-xhs-admin-token-2026` | 403 |
| 分类存在 | `mysql -e "SELECT id FROM my_xhs_product.t_category LIMIT 1"` | "分类不存在" |

## § ASCII流转图

```
curl POST /api/product/spu (X-Admin-Call + X-User-Id)
  → Gateway → my-xhs-product:19006 ProductController.createSpu()
    → @RateLimit Redis myxhs:product:create:{userId} (5/min)
    → MySQL: SELECT t_category WHERE id=?
    → 雪花ID生成
    → MySQL: INSERT INTO t_spu
    → afterCommit → 布隆过滤器.add(spuId)
    → 返回 {spuId}
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl POST /api/product/spu` | 200, `data.spuId` |
| MySQL | `SELECT * FROM t_spu WHERE id=?` | 1行, status=1 |
| Redis Bloom | `redis BF.EXISTS` | 1 (存在) |
| MySQL Canal | 等2-3秒后在ES查 | 新SPU可搜索 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | @RateLimit 5/min | ✅ |
| 并发 | afterCommit事务保护布隆 | ✅ |
| 安全 | X-Admin-Call校验 | ✅ |
| 微服务 | 无跨服务调用 | ✅ |

## § curl

```bash
curl -s -i -X POST http://localhost:19000/api/product/spu \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试商品","categoryId":1,"brandId":null,"description":"测试描述","images":[]}'
```
