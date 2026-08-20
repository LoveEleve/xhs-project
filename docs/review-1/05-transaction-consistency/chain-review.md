# 交易主链路首轮 review

## 1. 审查路径

本轮沿以下顺序追踪：

```text
order create
  -> inventory preDeduct
  -> payment create/callback
  -> order paid
  -> inventory confirm
  -> cancel/refund/release
  -> coupon return
```

## 2. 已确认的设计优点

1. order 状态更新大量使用带当前状态条件的乐观锁。
2. payment 回调只处理 status=0，重复回调会被状态挡住。
3. inventory 的预扣、释放、退款回补具备部分 Redis/Lua 原子操作和幂等 key。
4. TCC 库存分支使用 fence 表处理重复 confirm/cancel 与悬挂。
5. 订单事件使用事件序号与唯一索引防重复写入。

## 3. 核心问题

### 3.1 库存确认的可靠性断点

支付成功后，order 通过异步线程触发 inventory confirm：

- `OrderService.java:716-717`：支付成功后异步确认库存
- `OrderService.java:732-746`：失败只记录日志
- `InventoryService.java:350-403`：先删除 Redis 预扣，再发 CONFIRM MQ
- `InventoryService.java:571-615`：MQ 失败时取消 outbox

这造成：

```text
Redis 预扣删除
  -> MQ 发送失败
  -> outbox 删除
  -> order 只记日志
  -> 没有同一业务事件的稳定重试凭据
```

正式问题见：

- `02-findings/high/F-004-confirm-inventory-loses-retry-state-on-mq-failure.md`
- `02-findings/high/F-005-inventory-outbox-collapses-actions.md`

## 4. 当前交易状态模型

目前存在三层状态：

1. 订单主状态：MySQL 分片订单表
2. 库存快速状态：Redis total/bucket/prededuct
3. 库存持久状态：MySQL available/locked + inventory outbox

支付成功后的订单状态推进与库存确认不是同一事务，属于最终一致性模型。因此补偿不是“可选优化”，而是主链路的一部分。

## 5. 补偿与回调下钻结果

### 5.1 补偿路由缺失会吞消息

`OrderCompensationConsumer.java:92-107` 在无法取得 userId 映射时直接 return，未抛异常；因此 RocketMQ 不会重试。该问题已记录为：

`02-findings/high/F-006-compensation-message-acknowledged-when-routing-data-missing.md`

### 5.2 支付成功与库存确认是两条独立收敛链

支付服务会先将支付单设为成功，再发 MQ/同步通知订单；订单将状态设为已支付后，再异步确认库存。这个顺序意味着：

- 钱已成功不代表订单已成功收敛
- 订单已支付不代表库存持久层已确认
- 任意一段失败都必须依赖明确补偿，而不是日志

当前确认库存链的补偿证据不足，已由 F-004/F-005 覆盖。

### 5.3 订单状态事件本身有乐观锁，但外部副作用不在同一事务

`OrderEventService.appendEvent()` 使用事务和乐观锁维护订单事件/状态；库存、优惠券则通过 Feign/MQ 在事务外完成。

因此不能只证明订单状态机正确，还必须证明每个外部副作用都有可靠的 outbox、补偿和可重入路径。

### 5.4 退款回补的幂等粒度错误

订单退款会按 SKU 调库存回补，但库存服务用 `inventory:refund:{orderId}` 做幂等键。多 SKU 订单中，第一个 SKU 设置成功后，后续 SKU 会被直接跳过。

正式问题见：

`02-findings/critical/F-007-refund-restore-idempotency-collapses-multi-sku-orders.md`

### 5.5 退券的数据库与 Redis 更新之间缺少可靠补偿

退券 MySQL 事务提交后才更新 Redis；afterCommit 失败不会让 order 侧感知，也没有可重试记录。

正式问题见：

`02-findings/high/F-008-coupon-return-after-commit-has-no-redis-repair-path.md`

### 5.6 退款成功通知补偿的去重键缺失

`RefundNotifyCompensateJob` 读取去重键但从不写入，导致已成功通知的退款记录每 3 分钟被重复处理并产生"需人工处理"误报。

正式问题见：

`02-findings/medium/F-009-refund-notify-dedup-key-never-set.md`

## 6. 交易链路首轮结论

交易一致性链路的核心缺陷集中在"最终一致性副作用没有可靠的重试凭据"：

- 库存确认先删状态再发消息，MQ 失败后无重试凭据（F-004）
- 库存 Outbox 唯一键不含 action，多动作互相覆盖（F-005）
- 补偿消费者路由缺失时吞消息（F-006）
- 退款回补幂等键粒度错误，多 SKU 只回补一个（F-007）
- 退券 afterCommit 的 Redis 失败无补偿（F-008）
- 退款通知补偿去重键缺失（F-009）

共同根因：系统依赖 Redis 键、日志和"对账兜底"来承担最终一致性，但这些兜底在状态粒度、幂等键、重试凭据上并不一致。

### 5.7 退款成功回调无条件返回成功，掩盖订单侧失败

`notifyRefundSuccess` 端点无论订单是否真正更新都返回 `R.ok()`，导致 `RefundNotifyCompensateJob` 误判补偿成功、停止重试，订单可能永久卡在"已支付"而钱已退。

正式问题见：

`02-findings/high/F-010-refund-success-callback-always-ok.md`

### 5.8 支付金额未二次校验，getOrderPayAmount 语义与注释不符

`pay()` 直接用请求传入金额，未对订单真实 `payAmount` 校验；`getOrderPayAmount` 对任何状态订单都返回金额，与注释声称的"仅待支付返回金额"不符。

正式问题见：

`02-findings/medium/F-011-pay-amount-not-revalidated-and-semantics-mismatch.md`

## 7. 交易链路深审结论（累计 F-004 ~ F-011）

交易链路的确定性缺陷已经从"状态机/幂等键"深入到"回调语义与补偿链"：

- 回调返回值的语义丢失（F-010）会让补偿任务误判成功
- 补偿去重键缺失（F-009）会让补偿任务反复执行
- 二者叠加会形成"该重试的没重试、不该重试的反复重试"
- 金额权威校验缺失（F-011）使资金安全过度依赖内部信任边界

## 8. 剩余下钻项

1. coupon use/return 与订单取消、退款的并发状态条件
2. 补偿消息是否携带完整业务上下文，以及 DLQ 是否有人工可恢复路径
3. 分库分表下订单映射表写入失败后的修复任务覆盖范围
