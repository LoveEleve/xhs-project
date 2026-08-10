# my-xhs-coupon 业务逻辑分析

## 一、券类型

| 类型 | 说明 | 参数 |
|------|------|------|
| 满减券 | 满X减Y | minAmount=门槛, discountValue=减额 |
| 折扣券 | 打X折 | minAmount=0, discountValue=折扣(0-100) |
| 无门槛券 | 无门槛减Y | minAmount=0, discountValue=减额 |

## 二、领券流程

```
用户申领 → claimCoupon()
  → Lua原子 check(库存>0+未超限+模板有效)
    → 成功 1: INSERT t_coupon_outbox(claimNo) → 同步发MQ
      → MQ超时/失败: Job重发
      → CouponClaimConsumer: ON DUPLICATE KEY(uk_claim_no)
    → 失败 -1: 库存不足
    → 失败 -2: 每人限领超限(perUserLimit)
    → 失败 -3: 模板无效
```

## 三、用券流程

```
OrderFeign → useCoupon(userId, couponId, orderAmount)
  → AmountValidator: orderAmount >= minAmount (满减券) / >= 0.01 (折扣券/无门槛)
  → ExpireValidator: validStart <= now <= validEnd
  → StatusValidator: status == AVAILABLE/CLAIMED
  → 乐观锁 markUsed: UPDATE t_user_coupon SET status=USED WHERE id=? AND status=AVAILABLE
    → affected=0: 并发已用 → 抛异常
  → 返回 discountAmount (满减=discountValue, 折扣=orderAmount*discountValue/100)
```

## 四、退券流程

```
Order Feign → returnCoupon(userId, couponId)
  → @Transactional
  → 乐观锁退券: UPDATE t_user_coupon SET status=AVAILABLE WHERE id=? AND status=USED
    → affected=0: 并发已退 → 抛异常
  → 回退Redis库存: INCR myxhs:coupon:stock:{templateId}
```

## 五、查询券列表

```
getUserCoupons(userId, status):
  SELECT * FROM t_user_coupon WHERE user_id=? AND (status=? if present)
  → 关联查询券模板(模板缓存优先)

getAvailableCoupons(userId):
  SELECT * FROM t_user_coupon WHERE user_id=? AND status=AVAILABLE
  → filter validEnd > now → 计算可用
```

## 六、折扣计算

```
满减: discount = discountValue (固定)
折扣: discount = orderAmount * discountValue / 100.0 (百分比)
getCouponDiscount(): 只计算不核销——责任链校验但不写 status
```
