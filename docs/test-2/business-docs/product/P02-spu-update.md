# P02: 更新SPU — PUT /api/product/spu/{spuId}

## § 源码分析

- **Controller**: `ProductController.java:75` → `@PutMapping("/spu/{spuId}")`, 参数 `@RequestHeader X-User-Id` + `@RequestHeader X-Admin-Call` + `@Valid @RequestBody SpuUpdateRequest`
- **Service**: `SpuService.java:287` → `updateSpu()`
  - 校验SPU存在: `spuMapper.selectById(spuId)` → null抛异常
  - `LambdaUpdateWrapper<Spu>` 按字段部分更新(name/description/images等) — 防并发丢失更新
  - `afterCommit`: `Delete Redis SPU缓存` — 下次读触发CacheAside重建
- **下游**: MySQL `t_spu` LambdaUpdate + Redis `DEL myxhs:product:spu:{spuId}`

## § 业务逻辑

管理员提交SPU更新(name/description/images) → Admin校验(X-Admin-Call) → 限流(5/min) → 校验SPU存在 → LambdaUpdateWrapper部分字段更新(两个并发请求分别改name和description不会互相覆盖) → 事务提交后异步删除Redis缓存 → 下次读触发缓存重建 → 返回OK

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | `X-Admin-Call: my-xhs-admin-token-2026` | 403 |
| SPU已存在 | `mysql -P 3306 -e "SELECT id FROM my_xhs_product.t_spu WHERE id=?"` | SPU不存在抛异常 |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_spu LIMIT 1"` | 500 |

## § ASCII流转图

```
curl PUT /api/product/spu/{spuId} (X-Admin-Call + X-User-Id)
  → Gateway → ProductController.updateSpu()
    → X-Admin-Call校验 → 非法? → 403
    → MySQL: SELECT * FROM t_spu WHERE id=? (存在性)
    → MySQL: UPDATE t_spu SET name=?, description=?, images=? WHERE id=?
    → afterCommit → Redis DEL myxhs:product:spu:{spuId}
    → 返回 OK
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -i -X PUT http://localhost:19000/api/product/spu/{spuId} -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: ..." -H "Content-Type: application/json" -d '{"name":"新名称"}'` | 200 |
| HTTP(无token) | `curl -i -X PUT ...` | 403 |
| MySQL | `mysql -P 3306 -e "SELECT name FROM my_xhs_product.t_spu WHERE id=?"` | "新名称" |
| Redis | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.get('myxhs:product:spu:{id}'))"` | None(缓存已删除) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | LambdaUpdateWrapper防丢失更新 | ✅ |
| 安全 | X-Admin-Call校验 | ✅ |
| 数据一致性 | afterCommit删缓存, 下次读查新值 | ✅ |
| 幂等 | 重复更新相同字段不报错 | ✅ |

## § curl

```bash
curl -s -i -X PUT http://localhost:19000/api/product/spu/{spuId} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"name":"更新名称","description":"新描述"}'
```
