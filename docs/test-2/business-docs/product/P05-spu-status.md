# P05: 上下架 — PUT /api/product/spu/{spuId}/status

## § 源码分析

- **Controller**: `ProductController.java:119` → `@PutMapping("/spu/{spuId}/status")`, 参数 `@RequestParam Integer status` + `@RequestHeader X-Admin-Call`
- **Service**: `SpuService.java` → `updateSpuStatus()`
  - 校验 `status`: 仅接受 `ProductStatus.ON_SHELF(1)` 或 `ProductStatus.OFF_SHELF(0)` — Controller层提前校验避免穿透Service
  - `spuMapper.update(null, new LambdaUpdateWrapper<Spu>().eq(Spu::getId, spuId).set(Spu::getStatus, status))`
  - `afterCommit`: 删除Redis SPU缓存(`evictSpuCache(spuId)`) — 下次读触发CacheAside重建
- **下游**: MySQL `t_spu` UPDATE status字段 + Redis `DEL myxhs:product:spu:{spuId}`

## § 业务逻辑

管理员切换SPU上下架 → Admin校验(X-Admin-Call) → 限流(5/min) → Controller层status值校验(只接受0或1) → Service构建 `UPDATE t_spu SET status=? WHERE id=?` → 事务提交后删除Redis SPU缓存 → 下架商品不再出现在SPU列表/ES搜索中

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | `X-Admin-Call: my-xhs-admin-token-2026` | 403 |
| SPU已存在 | `mysql -P 3306 -e "SELECT id FROM my_xhs_product.t_spu WHERE id=?"` | WHERE匹配0行(不报错) |
| status值有效 | 仅0或1 | PARAM_INVALID |

## § ASCII流转图

```
curl PUT /api/product/spu/{spuId}/status?status=0 (X-Admin-Call)
  → Gateway → ProductController.updateSpuStatus()
    → X-Admin-Call校验 → 非法? → 403
    → Controller: status值校验(0/1) → 无效? → PARAM_INVALID
    → MySQL: UPDATE t_spu SET status=0 WHERE id=?
    → afterCommit → Redis DEL myxhs:product:spu:{spuId}
    → 返回 OK
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP(下架) | `curl -i -X PUT "http://localhost:19000/api/product/spu/{spuId}/status?status=0" -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: ..."` | 200 |
| HTTP(上架) | `curl -i -X PUT "http://localhost:19000/api/product/spu/{spuId}/status?status=1" -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: ..."` | 200 |
| HTTP(无token) | `curl -i -X PUT "http://localhost:19000/api/product/spu/{spuId}/status?status=0"` | 403 |
| HTTP(无效值) | `curl ... status=2 ...` | PARAM_INVALID |
| MySQL | `mysql -P 3306 -e "SELECT id,status FROM my_xhs_product.t_spu WHERE id=?"` | status=0或1 |
| Redis | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.get('myxhs:product:spu:{spuId}'))"` | None(删缓存) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | X-Admin-Call + Controller校验status | ✅ |
| 幂等 | 重复下架/上架不报错 | ✅ |
| 数据一致性 | afterCommit删缓存, 下次读查新值 | ✅ |
| 并发 | UPDATE直接写MySQL无read-then-write | ✅ |
| 稳定性 | 无效status值在Controller层拦截 | ✅ |

## § curl

```bash
TOKEN=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456","captcha":"dummy"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 下架商品
curl -s -i -X PUT "http://localhost:19000/api/product/spu/{spuId}/status?status=0" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"

# 上架商品
curl -s -i -X PUT "http://localhost:19000/api/product/spu/{spuId}/status?status=1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```
