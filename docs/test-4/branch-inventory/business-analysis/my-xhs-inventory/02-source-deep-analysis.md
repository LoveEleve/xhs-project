# my-xhs-inventory 源码深度分析

## 1. 模块定位

库存服务处于订单、支付、取消和退款链路中间，以 Redis 分桶承载实时库存，以 MySQL 保存异步持久化状态，以 RocketMQ/Outbox/补偿/对账处理最终一致性。另有 TCC 路径维护 MySQL freezing_stock。

```text
Order transaction
  -> OrderTransactionConsumer
  -> Redis total/bucket + prededuct Hash/ZSet
  -> INVENTORY_TOPIC / Outbox
  -> InventoryDeductConsumer
  -> MySQL available/locked
  -> timeout/compensation/reconcile
```

TCC：`Try -> freezing_stock -> Confirm/Cancel`。

## 2. 初始化、reinit、resize

初始化使用 SKU 级 Redis lock，从 MySQL 读取 total，均匀写入 bucket、total 和 bucket-count。中途失败会留下半初始化状态；锁只防并发，不提供事务回滚。

`reinitStock` 清理并重建 Redis，但没有和 preDeduct/release/confirm/timeout 形成统一暂停屏障；并发预扣可能被重建快照覆盖。`resizeBuckets` 使用 Pipeline 逐桶读取/写入，Pipeline 不是 Lua/Redis Transaction，且超时任务可能并发修改旧桶。

## 3. 普通预扣

`preDeduct` 先写订单+SKU 幂等占位，再检查暂停、bucket-count、total，执行 `prededuct.lua`。Lua 按 userId 路由优先桶，库存不足时扫描其它桶，并写预扣 Hash/ZSet。Redis 成功后同步发送库存事件；发送失败会回滚 Redis。

已修复后，PRE_DEDUCT 发送失败会取消对应未发送 Outbox，避免 OutboxSender 后续补发导致 MySQL 幻影扣减。Redis、Outbox 和 MQ 仍不是同一事务。

幂等表没有完整的 processing/sent 状态。进程在 Redis 扣减、消息发送和返回之间崩溃时，后续重试可能被幂等占位挡住，需恢复状态设计。

## 4. Confirm、Release、Refund、TCC

Confirm 删除 Redis 预扣记录并发送 CONFIRM 更新 MySQL locked_stock；Release 将库存回到来源桶并发送 RELEASE；Refund Restore 增加 total/bucket 后发送 REFUND_RESTORE。每条链路跨 Redis、MySQL、MQ，无法依赖单个 Redis Lua 获得跨系统原子性。

TCC 使用 Fence 保护 Try/Confirm/Cancel 状态，但 Confirm/Cancel 对数据库影响行数和库存状态的校验不足。普通 Redis 分桶和 TCC freezing_stock 未统一，混用会产生两本库存账，InventoryReconcileJob 也可能覆盖 TCC 相关状态。

## 5. 消费者与任务

- `OrderTransactionConsumer` 按 SKU 消费订单事务消息；SKU 不存在时直接确认，可能留下订单已提交但库存未扣的业务缺口
- `InventoryDeductConsumer` 通过 msgId/事件版本保护 MySQL 更新，但部分 DB 异常只记日志不抛出，消息可能 ACK
- `InventoryCacheEvictConsumer` 当前实际只处理 DELETE；UPDATE/INSERT 跳过，外部 DB 直改不会自动失效缓存
- `InventoryOutboxSenderJob` 扫描 status=0，无 processing/租约；锁失效或重启时可能重复发送
- `InventoryCompensationJob` 对预扣记录不存在缺少“已完成/数据丢失”区分
- `PreDeductTimeoutJob` 按 ZSet 每 60 秒释放超时记录，和支付确认存在边界竞争
- `TccTimeoutJob` 使用 Fence 取消超时分支，但库存更新失败后的重试语义需确认
- `InventoryReconcileJob` 全量加载并以 Redis total 修复 MySQL，不能直接覆盖 TCC/在途消息

## 6. Redis Lua

- `prededuct.lua`：分桶检查、预扣、预扣记录和索引；当前 Java `ARGV[6]` 路由桶、`ARGV[7]` 过期秒数与 Lua 读取一致，未发现参数错位
- `release.lua`：来源桶/total 回退、删除预扣记录和索引
- `confirm.lua`：删除预扣记录和索引
- `reconcile_buckets.lua`：桶求和与 total 修复

Lua 只能保证单次 Redis 操作原子，不能保证 Redis、Outbox、MQ、MySQL、TCC Fence 的整体原子性。分桶 key 和 prededuct/index key 是否满足当前 Redis 拓扑，需要运行确认。

## 7. 分类问题

### 代码 Bug
- PRE_DEDUCT Outbox 残留已修复
- TCC Confirm/Cancel 影响行数校验不足
- UPDATE/INSERT 缓存失效事件实际跳过
- 消费者部分数据库异常只记录不抛出
- 空/非法消息路径校验不统一

### 业务逻辑
- TCC 与普通预扣库存账本未统一
- reinit/resize 可覆盖并发预扣
- SKU 不存在时订单库存结果缺少补偿
- Redis miss 查询与重建 total 的数量语义不同

### 分布式
- 事件版本不是严格动作序列，同毫秒事件可能互相跳过
- Outbox 无 claim/租约
- 幂等占位缺少处理中和恢复状态
- 对账与用户写操作没有统一屏障
- Redis/MySQL/Outbox/TCC 非原子

### 微服务
- Product Feign 异常路径 fail-open
- Order/Inventory/Product 的 SKU 状态契约需要统一
- Canal/MQ/Redis/MySQL 链路依赖外部配置和消费者

### 性能
- 同步 MQ 发送占用业务线程
- 全量库存对账加载内存
- HotSkuDetector 多次 Redis 往返且非原子
- resize/reinit 逐桶处理

### 工程
- Dockerfile 依赖外部 base image
- 配置存在明文凭据和固定 IP
- 初始化/迁移与实体字段契约可能漂移
- 测试原先缺 RedissonClient，已补齐

### 可观测性
- Outbox、补偿、TCC Cancel、版本跳过、重建和对账缺少统一指标
- MQ 异常被吞时可能无法触发可靠告警
- Actuator health details 可能暴露内部状态

## 8. 已修复与验证

### 代码/契约修复
- `InventoryService` 测试构造器已补齐 `RedissonClient`
- 初始化测试已同步生产带 TTL 的锁调用和完整初始化标记
- Outbox 唯一键从 `(order_id, sku_id)` 改为 `(order_id, sku_id, action)`，避免 PRE_DEDUCT/CONFIRM/RELEASE 互相覆盖
- `InventoryMapper.insertOutboxEvent` 的 `ON DUPLICATE KEY UPDATE` 补充 `action = VALUES(action)`，保持幂等时动作一致
- 正式 migration `V1__init_inventory.sql` 补齐 `t_tcc_fence`、`t_tcc_freeze_detail`、`t_inventory_outbox`、`t_inventory_compensation`、`t_inventory_prededuct_idem`

### 误报澄清（未修改）
- `prededuct.lua` 的 ARGV 下标与 Java 传参一致，不是参数错位
- 预扣超时任务的 `score` 是“创建时刻+1800s”，`rangeByScore(0, now+60s)` 只命中约 29 分钟前的记录，属于提前 1 分钟释放的合理设计，不是提前释放 bug

### 验证结果
- inventory 测试：15 个通过；common 测试：53 个通过
- inventory 已重新打包并启动，19009 health 为 `UP`
- 线上主/从 MySQL 已落地 DDL：
  - `t_inventory_prededuct_idem` 建表成功（此前不存在）
  - `t_inventory_outbox` 索引由 `uk_order_sku` 改为 `uk_order_sku_action(order_id,sku_id,action)`
- 实测链路通过：
  - preDeduct 成功（无 1146），Redis 扣减 + Outbox 写入 + MySQL available=97/locked=3
  - confirm 成功，MySQL locked 3→0
  - Outbox 中 PRE_DEDUCT 与 CONFIRM 各自独立一行，动作不再互相覆盖
- RocketMQ 手动创建 `INVENTORY_TOPIC`（broker autoCreateTopicEnable=false），preDeduct 才可发送成功
- AI 未纳入；鉴权仅基础检查

## 9. 待运行确认

- 实际 MySQL schema、索引和 freezing/outbox/compensation 表
- Redis Sentinel/Cluster 拓扑及 Lua 多 key slot
- MQ topic/tag、重试/DLQ 和消费者 ACK
- TCC 与普通预扣是否会混用
- reinit/resize/timeout 的并发窗口
- Product Feign 故障和内部调用实际行为
