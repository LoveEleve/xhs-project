# N03: 券模板详情 — GET /api/coupon/template/{id}

## § 源码分析

- **Controller**: `CouponController.java:73` → `@GetMapping("/template/{id}")`, 无鉴权
- **Service**: `CouponService.java:145` → `getTemplate()`
  - CacheAside: Redis `myxhs:coupon:template:{id}` (TTL=30min)
  - 命中 → 反序列化返回
  - 未命中 → MySQL `SELECT * FROM t_coupon_template WHERE id=?` → 回写Redis(30min)
- **下游**: Redis + MySQL

## § 业务逻辑

CacheAside模式 → Redis缓存命中返回 → 未命中查MySQL → 回写Redis(30min TTL) → 返回模板完整信息(type/discount/minAmount/perUserLimit/validEnd等)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 模板存在 | `SELECT id FROM t_coupon_template WHERE id=?` | 返回空/404 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/coupon/template/{id}` | 200, 含name/type/discountValue |
| Redis | `r.ttl('myxhs:coupon:template:{id}')` | ≤1800 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | CacheAside 30min TTL | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/coupon/template/$TEMPLATE_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/coupon/template/{id}
  → CouponController.getTemplate(id)
    → CacheAside: Redis GET myxhs:coupon:template:{id}
      → 命中 → 反序列化 → 返回
      → 未命中 → MySQL SELECT → Redis SET(30min) → 返回
```
