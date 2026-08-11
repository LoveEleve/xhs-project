# Coupon 模块 Review

## 业务逻辑（重点）
1. **[高] 优惠券在下单时从未被核销（useCoupon 死代码）→ 可无限次复用**
   - OrderService 只调用了 `getCouponDiscount`（算折扣，不核销）和 `returnCoupon`，**从未调用 `useCoupon`**
     （CouponFeignClient.useCoupon 定义但无调用方，CouponController /use 端点死代码）。
   - 结果：下单用券后 `user_coupon.status` 仍为 0(未使用)、`used_order_id` 为空。
   - 用户可用同一张券反复下单，每单都享受满减/折扣 → 大额资损。
   - 且 `returnCoupon` 依赖 `WHERE status=1 AND used_order_id=?`（CouponService.java:365），
     因从未 markUsed，退券永远是 no-op → 取消订单也不回退（虽然本就未用）。
   - **修复**：下单创建订单时应调用 `useCoupon`（乐观锁 markUsed + 绑定 orderId）；或在下单事务里核销。

## 分布式 / 一致性
2. **[中] 领券 Redis 库存(权威) + MQ/Outbox 落 MySQL**：Lua 扣库存 → syncSend + Outbox → Consumer 写 DB。
   syncSend 失败即回滚 Redis（+回滚 Outbox），强一致设计合理。靠 CouponReconcileJob 兜底。
3. **[低] 模板缓存空值"NULL" 字符串标记** (CouponService.java:449,468) 60s 防穿透 —— 可接受，但用魔法字符串非类型安全。
4. **hash tag `{%d}` 保证 stock/claimed 同 slot** —— 好。

## 校验 / 安全
5. **[低] getCouponDiscount 走责任链校验但不下单前锁定**——结合 #1，本质是没有"下单占用券"语义。
6. 用券归属校验（userId）、状态乐观锁 markUsed —— 逻辑本身正确，只是没被调用。
7. 退券 Redis 回退放在 afterCommit —— 避免 MySQL 回滚后 Redis 错 +1，正确。

## 结论
- 优惠券"领券/库存/退券"链路健壮；**致命缺陷在"下单用券"环节：核销从未发生**，属高优先级资损类缺陷。
