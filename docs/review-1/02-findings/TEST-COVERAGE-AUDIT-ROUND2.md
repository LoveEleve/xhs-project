# 测试覆盖审计 Round 2

## 自动化测试缺口

在 order/payment/inventory/coupon/search/home 测试目录中，未发现针对以下关键任务/消费者的直接自动化测试：

- `OrderCompensationConsumer`
- `RefundNotifyCompensateJob`
- `PaymentNotifyCompensateJob`
- `OrderMappingRepairJob`
- `CouponReconcileJob`
- `FeedCleanupJob`
- `IndexRebuildJob`
- `IncrementalIndexSyncJob`
- `RecommendComputeJob`
- `UnreadReconcileJob`

## 已发现的测试偏差

### 多 SKU只覆盖创建，不覆盖退款副作用

`OrderTransactionServiceTest` 的多 SKU用例只验证写入两条 `OrderItem`，没有触发退款回调，也没有核对库存。

### Controller 测试 mock 掉核心 Service

`OrderControllerTest.notifyRefundSuccess()` 对 `onRefundSuccess()` 使用 `doNothing()`，所以无论真实实现是否失败，测试都只能得到 200。

### Consumer/Job 缺乏故障注入

未发现对以下故障的自动化覆盖：

- MQ producer send failure
- MQ consumer DB failure
- Redis afterCommit failure
- Feign dependency timeout
- mapping database unavailable
- ES bulk partial failure
- XXL-Job inner exception with outer success reporting

## 对当前主线 Bug 的覆盖映射

| Bug | 是否有直接自动化测试 | 缺口 |
|---|---|---|
| F-006 | 否 | mapping 缺失时必须 throw/retry/DLQ |
| F-007 | 否 | 多 SKU refundRestore 每 SKU一次 |
| F-008 | 否 | MySQL commit 后 Redis 失败补偿 |
| F-010 | 否 | refund-success 失败语义传递 |
| F-013 | 部分 | AI list/detail/cancel 归属与角色 |
| F-016 | 否 | decrementRemainCount affected=0 |
| F-021 | 否 | behavior DB 失败重试 |
| F-023/F-042 | 否 | ES 半成品文档禁止写入 |
| F-024 | 否 | full rebuild 不清零互动计数 |
| F-036 | 否 | mapping 故障超过 1 小时补录 |
| F-039 | 否 | BFF 下游失败语义 |
| F-041 | 否 | XXL-Job success/fail 与产物一致 |
| F-043 | 否 | 目标校验异常时互动应失败 |

## 结论

当前自动化测试覆盖的是：

- 单服务方法正常分支
- Controller 参数与 HTTP 包装
- 少量状态机基础路径

没有覆盖：

- 跨服务最终一致性
- 补偿任务产物
- 多实体组合
- 故障注入
- 任务平台成功语义

因此“回归全绿”不能作为这些主线问题不存在的证据。