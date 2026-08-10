# N06: 可用优惠券 — GET /api/coupon/user/available

## § 源码分析

- **Controller**: `CouponController.java:107` → `@GetMapping("/user/available")`, 参数 `X-User-Id`
- **Service**: `CouponService.java:399` → `getAvailableCoupons()`
  - `SELECT * FROM t_user_coupon WHERE user_id=? AND status=AVAILABLE`
  - filter `validEnd > now()` (Java内存过滤过期券)
  - 关联券模板信息
- **下游**: MySQL t_user_coupon + t_coupon_template

## § 业务逻辑

查用户所有AVAILABLE状态的券 → Java内存过滤validEnd过期 → 关联模板信息 → 返回可用券列表(下单时展示)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/coupon/user/available` | 200, 数组 |
| MySQL | `SELECT COUNT(*) FROM t_user_coupon WHERE user_id=? AND status=1` | >= 返回长度 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 正确性 | 过滤validEnd已过期 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/coupon/user/available" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/coupon/user/available
  → CouponController.getAvailableCoupons(X-User-Id)
    → SELECT * FROM t_user_coupon WHERE user_id=? AND status=AVAILABLE
    → filter validEnd > now → 关联模板
    → 返回 List<UserCouponVO>
```
