# my-xhs-coupon 模块总览

## 1. 当前模块定位

优惠券服务（端口 19010），负责券模板管理、用户领券、用券核销、退券、过期与库存对账。位于订单链路之前：`order → coupon 折扣/用券/退券`。

## 2. 核心链路

```text
领券: Controller → 模板缓存校验 → claim_coupon.lua(原子扣库存+限领) → Outbox → MQ
     → CouponClaimConsumer(msgId幂等 + claim_no唯一键) → t_user_coupon → remain-1

用券: Order Feign → useCoupon → 责任链校验 → calculateDiscount → markUsed(status 0→1)

退券: Order 取消 → returnCoupon(status 1→0 幂等) → remain+1 → return_coupon.lua

过期/对账: XXL-Job batchExpire / CouponReconcileJob
```

## 3. 状态机

- `UserCoupon.status`：0 未用 → 1 已用（markUsed 乐观锁）；0 → 2 过期
- `CouponTemplate.status`：0/1 + 逻辑删除

## 4. 当前关键事实

- Lua 原子领券、限领和退券回补逻辑正确
- 消费幂等双层：msgId + claim_no 唯一键
- `useCoupon` 不扣 remain，退券却回补 → 存在“领→用→退→再领”的限领绕过与超发敞口（业务待确认）
- 对账“以 Redis 为准”与消费者异步扣 MySQL 双扣源，in-flight 窗口可能短暂不一致
- Outbox 表只置位不清理，无限增长
- 配置含明文凭据；服务内用户接口信任 `X-User-Id`

## 5. 范围

非 `target` 文件 35 个：顶层 2、主源码 24、资源 5、测试 4。
AI 排除；鉴权仅基础检查。
