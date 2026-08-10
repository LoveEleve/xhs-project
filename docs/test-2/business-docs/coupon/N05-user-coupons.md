# N05: 我的优惠券 — GET /api/coupon/user/list

## § 源码分析

- **Controller**: `CouponController.java:98` → `@GetMapping("/user/list")`, 参数 `X-User-Id` + optional `@RequestParam Integer status`
- **Service**: `CouponService.java:381` → `getUserCoupons()`
  - `SELECT * FROM t_user_coupon WHERE user_id=?` + (status不为空时 `AND status=?`)
  - 关联查询券模板
- **下游**: MySQL t_user_coupon + t_coupon_template

## § 业务逻辑

按userId查询用户所有券记录 → 可选status过滤(1=可用,2=已用,3=过期)→关联券模板信息 → 返回列表

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/coupon/user/list` | 200, 数组 |
| MySQL | `SELECT COUNT(*) FROM t_user_coupon WHERE user_id=?` | = 返回数组长度 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 直接查MySQL+索引 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/coupon/user/list" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# 只查已用券
curl -s "http://localhost:19000/api/coupon/user/list?status=2" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl GET /api/coupon/user/list?status=1
  → CouponController.getUserCoupons(X-User-Id, status)
    → SELECT * FROM t_user_coupon WHERE user_id=? AND status=?
    → 关联 t_coupon_template
    → 返回 List<UserCouponVO>
```
