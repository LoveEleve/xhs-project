# 转化前置：购物车与优惠券

## 业务问题（AI 能回答）
- 加购转化率（浏览→加购）、购物车放弃率（加购→下单）。
- 领券/用券/核销率、券到期预警、用券订单占比。

## 口径与定义
- **购物车**：Redis 权威（items/checked/sort 三结构），MQ 异步落库 t_cart_item；上限 50 种、单 skuId ≤99；匿名合并取 max。
- **券类型**：满减(minAmount 门槛) / 折扣(orderAmount×折扣%) / 无门槛；领券 Lua 原子 + Outbox；用券责任链 + 乐观锁。

## 数据来源与就绪度
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 加购转化率 | t_user_behavior + cart | ⚠️ 需漏斗粒度 |
| 购物车放弃率 | cart → order | ⚠️ |
| 领券/用券/核销率 | t_coupon_template / t_user_coupon | ✅ |
| 券到期预警 | t_coupon_template | ✅ |
| 用券订单占比 | t_user_coupon.used_order_id | ✅ |

## 关键不变量 / 可信边界
- 购物车权威在 **Redis**（易失）→ 历史漏斗分析受限。
- 券用/退乐观锁：`UPDATE status=USED WHERE status=AVAILABLE`，affected=0 并发冲突。
- 领券幂等：uk_claim_no 唯一索引。

## 关联诊断
- "订单下降"漏斗断点归因，购物车是**关键中间环节**。
- "营销效果差/券积压将过期" → 券维度诊断（就绪度好）。
