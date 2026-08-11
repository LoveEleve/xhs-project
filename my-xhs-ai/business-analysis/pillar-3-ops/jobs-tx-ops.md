# 定时任务 + 分布式事务排障（XXL-Job / TCC / 本地消息表 / 对账）

> my-xhs 的一致性靠**定时补偿 + 分布式事务**兜底。这些任务/事务的状态是排障关键。

## 业务问题（AI 能回答）
- **任务失败/积压**：XXL-Job 执行器是否在线、任务失败率。
- **补偿是否跟上**：本地消息表/Outbox 补发 Job 的积压。
- **TCC 异常**：悬挂、空回滚、超时未释放的冻结库存。
- **对账差异**：计数/购物车/订单 Redis 与 MySQL 不一致。
- **数据一致性最终状态**：是否收敛。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| 任务执行 | XXL-Job Admin(18080) | ✅ |
| 本地消息表积压 | t_local_message(status, retry_count) | ✅ |
| Outbox 积压 | t_coupon_outbox / t_inventory_outbox | ✅ |
| TCC 悬挂/超时 | t_tcc_fence(status) + TccTimeoutJob | ✅ |
| 对账差异 | 各域对账接口(counter/cart/order/inventory) | ✅ |

## 关键诊断点
1. **补偿任务清单**（9 服务启用 XXL-Job）：
   - LocalMessageRetryJob(30s 指数退避) / DeadLetterScanJob(每小时)
   - CouponOutboxSenderJob(5s) / InventoryOutboxSenderJob(5s)
   - PreDeductTimeoutJob(1min 超时回退) / TccTimeoutJob(60s, >10min 冻结支消)
   - CounterReconcileJob(凌晨3点) / CartReconcileJob / InventoryReconcileJob
   - FeedMessageRetryJob(30s/60s) / IncrementalIndexSyncJob / IndexRebuildJob / HotSearchJob
2. **TCC 一致性**：Fence 表防悬挂/空回滚；超时未确认库存靠 Job 自动释放（防泄漏）。
3. **对账**：以 MySQL 为准修正 Redis；对账差异是数据漂移的信号。
4. **任务依赖**：analytics 缺 `xxl.job.enabled=true` 未注册执行器（已知）。

## 关联
- 补偿任务积压 = 上游 MQ/DB 故障的**滞后信号** → 关联 mq-ops/mysql-ops。
- TCC/对账异常 → 关联 pillar-2 inventory/order 诊断。
