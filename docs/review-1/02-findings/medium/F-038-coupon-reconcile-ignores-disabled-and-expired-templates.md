# F-038 优惠券对账只扫描启用且未过期模板，历史库存漂移无法修复

## 严重度

Medium

## 涉及文件

- `my-xhs-coupon/src/main/java/com/myxhs/coupon/job/CouponReconcileJob.java:75-80`

## 现象

CouponReconcileJob 只查询：

- `status = 1`
- `valid_end > NOW()`
- `deleted = 0`

已下线、已过期但仍存在 Redis key 或用户券记录的模板不会进入对账。

## 证据

`CouponReconcileJob.java:75-80` 的查询条件明确排除了下线和过期模板。

## 影响

1. 下线/过期模板的 Redis stock 与 MySQL remain_count 漂移会长期保留。
2. 退券、历史查询、后台库存统计可能出现不一致。
3. 如果模板重新上线，旧漂移会重新成为线上库存错误。

## 修复建议

1. 对账范围应覆盖所有未删除模板，状态/有效期只影响是否允许领取，不应影响数据对账。
2. 对已删除模板保留单独历史对账/归档策略。
3. 对账结果区分“修复库存”和“禁止重新开放”，不要用业务可用性过滤技术一致性检查。

## 是否需要补充验证

需要构造下线/过期模板的 Redis/MySQL 数量差异，确认当前对账是否跳过。