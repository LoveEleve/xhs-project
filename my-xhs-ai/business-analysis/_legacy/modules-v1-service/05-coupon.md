# 05. 优惠券域（my-xhs-coupon）业务逻辑

> 端口 19007 | 9 端点 | Lua 原子领券 + Outbox 模式 + 责任链校验 + 乐观锁

## 一、业务定位
**电商营销工具**：通过券模板（满减/折扣/无门槛）刺激转化，同时是下单金额计算与营销成本核算的关键。

## 二、券类型与计算规则
| 类型 | 门槛 | 优惠计算 |
|------|------|---------|
| 满减券 | minAmount 满X | discount = discountValue（固定减额） |
| 折扣券 | 0.01 即可 | discount = orderAmount × discountValue / 100 |
| 无门槛券 | 0.01 即可 | discount = discountValue |

## 三、关键业务规则 / 不变量
1. **领券（Lua 原子）**：check 库存>0 + 未超 perUserLimit + 模板有效 → 写 Outbox → MQ；返回 -1库存不足 / -2超限 / -3模板无效。
2. **Outbox 幂等**：`uk_claim_no` 唯一索引，CouponClaimConsumer 用 ON DUPLICATE KEY。
3. **用券（责任链 + 乐观锁）**：Amount→Expire→Status 校验，再 `UPDATE status=USED WHERE status=AVAILABLE`，affected=0 表示并发已用→抛异常。
4. **退券**：乐观锁 `USED→AVAILABLE` + 回退 Redis 库存 INCR；@Transactional。
5. **getCouponDiscount 只算不核销**（下单时查询用）。

## 四、异常路径
- 并发用同一张券 → 乐观锁拦截，只成功一个。
- 退券与用券竞态 → 乐观锁保证单方向成功。
- 缓存陷阱：模板缓存 30min 不主动失效，validStart 变更后旧模板仍命中（需手动 DEL）。

## 五、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 数据就绪度 |
|------|------|-----------|
| 领券/用券/核销率 | t_coupon_template / t_user_coupon | ✅ |
| 券到期预警（剩余多将过期） | t_coupon_template | ✅ |
| 用券订单占比 | t_user_coupon.used_order_id | ✅ |
| 券成本/折扣对 GMV 影响 | 关联 t_order | ✅ |

> 营销效果分析的高价值域，且**数据就绪度好**（有唯一索引、模板表、乐观锁语义清晰）。PLAN V1 未把它列入，偏科。
