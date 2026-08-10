# N04: 领券 — POST /api/coupon/claim

## § 源码分析

- **Controller**: `CouponController.java:86` → `@PostMapping("/claim")`, 参数 `X-User-Id` + `@Valid @RequestBody ClaimCouponRequest`
- **Service**: `CouponService.java:170` → `claimCoupon()`
  - `claim_coupon.lua` 原子执行:
    - stock > 0? (库存检查)
    - GET claimed < perUserLimit? (每人限领)
    - DECR stock + INCR claimed → 返回 1
    - 失败: -1 库存不足, -2 已领超限, -3 模板无效
  - Lua成功→ INSERT t_coupon_outbox(claimNo, status=PENDING)
  - try 同步 Send MQ COUPON_CLAIM_TOPIC
    - 超时/失败 → catch → 不回滚(Job定时重发)
  - CouponClaimConsumer: UPSERT t_user_coupon(uk_claim_no 幂等)
- **下游**: Redis stock/claimed + MySQL outbox + MQ

## § 业务逻辑

Lua原子检查(库存>0+未超perUserLimit+模板有效) → DECR库存+INCR计数 → Outbox记录 → 同步发MQ → MQ超时由Job重发 → Consumer ON DUPLICATE uk_claim_no幂等

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 有可用模板 | `SELECT id FROM t_coupon_template WHERE status=1` | 404 模板不存在 |
| 未领超限 | `redis-cli GET myxhs:coupon:claimed:{id}:{userId}` | -2 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/coupon/claim -d '{...}'` | 200 |
| Redis | `r.get('myxhs:coupon:claimed:{tId}:{userId}')` | 1 |
| MySQL | `SELECT COUNT(*) FROM t_user_coupon WHERE claim_no=?` | 1(uk唯一) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Lua原子扣库存 | ✅ |
| 幂等 | Outbox+uk_claim_no双保险 | ✅ |
| 微服务 | MQ异步落库 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
# 创建模板后可用的ID
curl -s -X POST http://localhost:19000/api/coupon/claim \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"templateId":1}' | python3 -m json.tool
```

## § ASCII流转图

```
curl POST /api/coupon/claim + body{templateId}
  → Gateway → CouponController.claimCoupon(X-User-Id, templateId)
    → claim_coupon.lua: check stock>0 + claimed<perUserLimit
      → -1(库存不足) / -2(超限) / -3(无效)
      → 1(DECR stock + INCR claimed)
    → INSERT t_coupon_outbox(claimNo, PENDING)
    → try Send MQ COUPON_CLAIM_TOPIC
      → timeout? → catch → Job retry
    → Consumer: UPSERT t_user_coupon ON DUPLICATE uk_claim_no
```
