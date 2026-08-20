# F-009 退款成功通知补偿的"已通知"去重键从未写入，导致反复通知并产生误报

## 严重度

Medium

## 涉及文件

- `my-xhs-payment/src/main/java/com/myxhs/payment/job/RefundNotifyCompensateJob.java:119-123`
- `my-xhs-payment/src/main/java/com/myxhs/payment/job/RefundNotifyCompensateJob.java:160-171`
- 对照：`my-xhs-payment/src/main/java/com/myxhs/payment/job/PaymentNotifyCompensateJob.java:122`、`:147`

## 现象

退款成功通知补偿任务读取去重键 `myxhs:payment:refund:notified:{orderId}` 来决定是否跳过，但全代码库没有任何地方写入该 key。

## 证据

1. `RefundNotifyCompensateJob.java:119-120` 读取 `notifiedKey` 并判断是否存在。
2. `RefundNotifyCompensateJob.java:160-171` 成功通知后只 `delete(countKey)` 和 `compensated++`，从不 `set(notifiedKey, ...)`。
3. `rg "payment:refund:notified"` 只有这一处读取，无写入。
4. 对照同类 `PaymentNotifyCompensateJob.java:147` 在成功通知后会 `set(notifiedKey, "1", Duration.ofHours(1))`，说明退款侧遗漏了对称的写入。

## 触发条件

1. 订单全额退款成功，且 `success_at` 超过 5 分钟补偿窗口。
2. 补偿任务每 3 分钟扫描 `t_refund WHERE status = 1`，同一记录被反复命中。

## 影响

1. 已成功通知过订单的退款记录，会在每个补偿周期被重新处理。
2. 重复 `notifyRefundSuccess` 因订单已退款返回失败，触发重试计数递增。
3. 计数超过 10 后持续打印"需人工处理"告警，形成大量误报，掩盖真实需要人工介入的退款。
4. 造成每 3 分钟一轮的无意义 Feign 调用，污染日志与告警。

## 修复建议

1. 在退款成功通知成功后，写入 `myxhs:payment:refund:notified:{orderId}`（带合理 TTL），与 payment 侧补偿对齐。
2. 或改为依赖 `t_refund` 自身的终态/通知状态字段，而不是只靠 Redis 计数与去重键。
3. 修复后验证：退款成功通知完成后，补偿任务不再重复命中该记录。

## 残余风险

即使补上去重键，也需保证"通知成功但去重键写入失败"的窗口不导致重复通知；由于订单侧乐观锁幂等，数据无损坏，主要是运维噪声。

## 运行态证据（2026-08-18）

已从 `/data/workspace/my-xhs/logs/my-xhs-payment.log` 观察到退款通知补偿任务连续运行：

- 12:06：`退款成功通知补偿完成: 重新通知 5 条`
- 12:09：`退款成功通知补偿完成: 重新通知 5 条`
- 12:12：`退款成功通知补偿完成: 重新通知 5 条`
- 12:15：`退款成功通知补偿完成: 重新通知 5 条`
- 12:18：`退款成功通知补偿完成: 重新通知 5 条`
- 12:21：`退款成功通知补偿完成: 重新通知 5 条`

这已经把原先的静态推断升级为运行态证据：同一批退款记录在每个补偿周期持续被重新处理。

## 是否需要补充验证

仍建议关联具体 `orderId/refundNo` 与 `notifiedKey`，确认这些 5 条是否就是同一批记录；但“补偿任务持续重复处理”的现象已被日志直接证实。