# F-004 库存确认先删除 Redis 预扣记录，MQ 失败后又取消 outbox，导致确认事件失去可靠重试依据

## 严重度

Medium

## 涉及服务

- `my-xhs-order`
- `my-xhs-inventory`

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:716`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:732`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:350`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:388`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:398`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:571`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:600`

## 现象

支付成功后，订单异步调用库存确认。库存确认流程先用 Lua 删除 Redis 预扣记录，然后再发送 CONFIRM 事件。

如果 MQ 发送失败：

1. Redis 预扣记录已经删除
2. `sendInventoryEvent()` 删除待发送 outbox
3. order 侧只记录日志并认为依赖对账兜底
4. 没有稳定的事件载荷或预扣记录可供后续重试

## 触发条件

1. 订单已支付成功
2. `confirmDeduct()` 读取到 Redis 预扣记录
3. `confirmScript` 删除预扣 hash/index
4. `syncSend` 超时、返回非 SEND_OK 或抛异常

## 证据

1. `InventoryService.confirmDeduct()` 在 `:388-393` 先执行 `confirmScript`，日志随后记录确认结果。
2. 只有删除预扣记录后，才在 `:398-402` 调用 `sendInventoryEvent(..., "CONFIRM")`。
3. `sendInventoryEvent()` 在 `:600-604` 对发送失败执行 `cancelOutboxEvent()`；异常路径 `:606-614` 同样删除待发送 outbox。
4. order 侧 `confirmInventoryDeduct()` 在 `OrderService.java:732-746` 只是异步记录失败，不阻塞支付成功状态，也不创建新的确认补偿任务。
5. `sql/mysql-inventory-init.sql:47-58` 中 `t_inventory_outbox` 的唯一键只有 `(order_id, sku_id)`，不包含 `action`；`InventoryMapper.insertOutboxEvent()` 又使用 `ON DUPLICATE KEY UPDATE ... action = VALUES(action)`。因此同一订单 SKU 的 PRE_DEDUCT/CONFIRM/RELEASE 事件会共享一行，竞态时后写 action 可以覆盖先写 action。

## 影响

1. Redis 已经认为预扣被确认并移除，但 MySQL 的 `locked_stock` 没有减少。
2. outbox 被删除后，常规 outbox sender 没有可重试记录。
3. 订单支付成功，但 `locked_stock` 虚高残留；`InventoryReconcileJob` 只以 Redis total 修正 `available_stock`（`InventoryReconcileJob.java:104` 的 `updateAvailableStockOnly`），不修复 `locked_stock`。
4. 因此 `available_stock` 正确（无超卖），但 `locked_stock` 长期虚高，影响库存管理准确性，长期累积。

## 修复建议

1. 不要在确认事件可靠落盘/可靠发送前删除唯一的预扣状态。
2. 将“确认事件”作为持久 outbox 的幂等状态：先写 outbox，再尝试发送；发送失败保留 `status=0`，由 sender 重试。
3. 让确认操作具备可重入语义：重复 confirm 不应重复扣减，但必须仍能修复 MySQL `locked_stock`。
4. 订单侧将确认失败纳入明确的补偿队列，而不是只依赖日志和模糊的对账兜底。
5. 对账任务需要增加 `locked_stock` 的核对，不能只修 `available_stock`。

## 残余风险

即使保留 outbox，也需要定义 syncSend 成功但消费者未落库、消费者落库后 ack 丢失、重复发送等状态，并确保 consumer 以 `(orderId, skuId, action)` 做幂等。

## 是否需要补充验证

需要使用测试替身模拟 MQ 发送失败，验证失败后 `locked_stock` 是否虚高残留、`available_stock` 是否能被对账修正。