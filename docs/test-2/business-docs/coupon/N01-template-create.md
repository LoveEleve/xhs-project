# N01: 创建优惠券模板 — POST /api/coupon/template

## § 源码分析

- **Controller**: `CouponController.java:49` → `@PostMapping("/template")`, 参数 `@Valid @RequestBody CreateTemplateRequest` + `X-Admin-Call`
- **Service**: `CouponService.java:84` → `createTemplate()`
  - 校验 validEnd > validStart
  - `INSERT INTO t_coupon_template` → 获取 template.id
  - `SETNX myxhs:coupon:stock:{template.id}` 初始化库存(防止并发重复初始化)
- **鉴权**: `isAdminCall(X-Admin-Call)` → 403 如果未配置 adminToken
- **下游**: MySQL t_coupon_template + Redis stock

## § 业务逻辑

AdminToken校验 → 参数校验(validEnd>validStart) → INSERT MySQL生成模板ID → SETNX初始化Redis库存 → 返回模板VO

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | Header `X-Admin-Call` 匹配配置 | 403 |
| 有效期 | `validEnd > validStart` | Service层抛异常 |
| RateLimit | 5次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/coupon/template -d '{...}'` | 200, 含id字段 |
| MySQL | `SELECT * FROM t_coupon_template WHERE id=?` | 一行 |
| Redis | `r.get('myxhs:coupon:stock:{id}')` | = totalCount |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | AdminToken + RateLimit | ✅ |
| 并发 | SETNX防库存重复初始化 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/coupon/template \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -H "Content-Type: application/json" \
  -d '{
    "name":"新人10元券",
    "type":1,
    "discountValue":"10.00",
    "minAmount":"50.00",
    "totalCount":100,
    "perUserLimit":1,
    "validStart":"2026-01-01T00:00:00",
    "validEnd":"2026-12-31T23:59:59"
  }'
```

## § ASCII流转图

```
Admin创建券模板 → curl POST /api/coupon/template + X-Admin-Call
  → CouponController.createTemplate()
    → AdminToken校验 → validEnd>validStart → INSERT t_coupon_template
    → SETNX myxhs:coupon:stock:{id} = totalCount
    → 返回 CouponTemplateVO
```
