# 8 个待量化问题：运行态验证计划

## 总原则

- 先采集基线，再执行单个验证，不批量注入故障。
- 每个用例必须同时记录 HTTP、MySQL、Redis、MQ/Outbox、ES/任务状态。
- 测试数据使用独立 ID 段，验证后清理。
- 任何故障注入前先确认当前服务、MQ、Redis、数据库运行状态。

## RV-004：库存 confirm 失败后的 locked_stock

### 目标

确认 MQ CONFIRM 发送失败后：Redis 预扣、Outbox、MySQL available_stock、locked_stock 的最终状态。

### 步骤

1. 创建单 SKU 订单并完成预扣。
2. 记录 Redis total/bucket/prededuct、MySQL available/locked、Outbox。
3. 在 confirm 发送点使用测试替身或隔离 MQ producer，使 CONFIRM 发送失败。
4. 等待 `InventoryReconcileJob` 执行。
5. 再次核对上述状态。

### 判定

- `locked_stock` 持续虚高且无补偿：保留。
- 有可靠补偿能修复 locked：降级为工程风险。

## RV-005：Inventory Outbox action 粒度

### 目标

确认同一 order+sku 的 PRE_DEDUCT/CONFIRM/RELEASE/REFUND_RESTORE 是否在异常并发下互相覆盖。

### 步骤

1. 使用同一 pseudoOrderId+skuId 生成多个 action。
2. 控制第一个 action 处于 pending，再写入第二个 action。
3. 检查 `t_inventory_outbox` 行数、action、status、created_at。
4. 触发 sender，检查 MQ topic 与 `markOutboxSent` 结果。

### 判定

- 关键 action 被覆盖或误标：保留。
- 只有顺序 happy path 覆盖、异常路径可恢复：降级。

## RV-012：索引计数源滞后覆盖

### 目标

确认点赞后编辑笔记时，Canal 全量索引是否会把 ES likeCount 回退。

### 步骤

1. 记录 analytics Set、counter Redis、counter DB、ES likeCount。
2. 点赞并等待 analytics Set 更新，暂不等待 counter 对账。
3. 修改笔记触发 Canal 索引。
4. 观察 ES likeCount 是否下降。
5. 等待 counter reconcile，再观察是否恢复。

### 判定

- 发生回退且恢复依赖异步对账：保留 Medium。
- 无回退或版本/字段更新保护有效：移出主 findings。

## RV-015：坏事务消息 ACK

### 目标

确认缺少 orderNo/userId 的订单事务消息是否直接 ACK。

### 步骤

1. 构造隔离测试 Topic 消息，缺少一个必填字段。
2. 观察 consumer 日志、reconsumeTimes、DLQ。
3. 核对是否产生库存预扣。

### 判定

- 直接 ACK 且无告警/补偿：保留 Medium。
- 进入重试/DLQ：移出主 findings。

## RV-025：推荐质量分读取滞后计数

### 目标

确认质量分是否在 counter 对账前固化旧 likeCount。

### 步骤

1. 记录 analytics Set、counter DB、`t_item_feature.quality_score`。
2. 点赞后立即运行特征任务。
3. 比较质量分使用的计数源与最终 analytics 权威值。
4. 对账后再次运行并比较。

### 判定

- 质量分可固化旧值：保留 Medium。
- 任务前置确保对账或读取权威源：移出主 findings。

## RV-037：库存补偿 bucket

### 目标

确认补偿释放是否恢复实际来源 bucket，而非固定 bucket0。

### 步骤

1. 选择能路由到非 0 bucket 的 userId。
2. 创建并预扣 SKU，记录 prededuct hash 中 bucket 字段。
3. 让正常 release 失败，生成 compensation 记录。
4. 执行补偿任务。
5. 比较原 bucket、bucket0、total。

### 判定

- 原 bucket 未恢复、bucket0 增加：保留 Medium。
- 补偿脚本实际从 prededuct 读取原 bucket：移出主 findings。

## RV-038：过期/下线优惠券对账范围

### 目标

确认对账任务是否跳过已下线或过期模板的库存漂移。

### 步骤

1. 建立独立测试模板，制造 Redis/MySQL remain_count 差异。
2. 分别设置模板为启用未过期、下线、已过期。
3. 执行 `couponReconcileJob`。
4. 检查三种状态是否都被修复。

### 判定

- 只修启用未过期：保留 Medium。
- 所有未删除模板都被对账：移出主 findings。

## RV-040：FeedCleanup 节流

### 目标

确认 cleanup 任务是否按 100 个 key 节流。

### 步骤

1. 静态证据已确认 count 在循环内重置。
2. 使用代码级单测或受控 mock 验证 sleep 调用次数。
3. 不在真实 Redis 上批量制造大量 key。

### 判定

- sleep 调用次数为 0：保留 Low/Medium 工程问题。
- 修复后再验证。

## 结果记录格式

每个 RV 用例完成后记录：

- 基线时间
- 测试数据 ID
- 执行命令/入口
- HTTP 结果
- DB 结果
- Redis 结果
- MQ/Outbox 结果
- ES/任务结果
- 最终判定：保留/降级/移除
- 未决原因
