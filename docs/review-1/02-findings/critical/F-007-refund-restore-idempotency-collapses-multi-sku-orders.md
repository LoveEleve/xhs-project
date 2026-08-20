# F-007 退款回补以订单为唯一幂等键，多 SKU 订单只会回补一个 SKU

## 严重度

Critical

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:798-818`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:669-680`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:700-710`

## 现象

订单退款回调会遍历订单明细，为每个 SKU 调用 `inventoryFeignClient.refundRestore()`。库存服务却使用只有订单维度的 Redis 幂等键：

```text
inventory:refund:{orderId}
```

第一个 SKU 成功设置该 key 后，同一订单的所有后续 SKU 都会被视为重复请求并直接跳过。

## 触发条件

1. 一个订单包含两个或更多 SKU
2. 订单进入退款成功回调
3. order 逐个调用 `refundRestore(orderId, skuId, quantity)`

## 证据

1. `OrderService.restoreStockOnRefund()` 遍历每个 `OrderItem`，逐 SKU 调用库存回补：`OrderService.java:800-808`。
2. `InventoryService.refundRestore()` 的幂等 key 只由 `orderId` 构成：`:674-677`。
3. 第一个 SKU 设置成功后，后续 SKU 在 `:678-680` 直接 return，不会执行 Redis INCR，也不会发送 `REFUND_RESTORE` MQ。

## 影响

1. 多 SKU 订单退款时，只有第一个被处理的 SKU 会回补库存。
2. 其他 SKU 的 Redis available stock 和 MySQL available_stock 都不会增加。
3. 退款结果表面成功，但库存永久少账，直到人工对账发现。
4. 该问题不是重复退款风险，而是幂等粒度错误导致的确定性漏处理。

## 修复建议

1. 幂等键至少改为 `inventory:refund:{orderId}:{skuId}`。
2. 更稳妥的是使用退款业务事件 ID，并把 action 纳入唯一约束。
3. `REFUND_RESTORE` 的 Outbox 唯一键、consumer 版本键也必须使用同一业务粒度。
4. 增加多 SKU 退款测试，断言每个 SKU 的 Redis 和 MySQL 库存都回补一次。

## 残余风险

当前 `t_inventory_outbox` 唯一键还只有 `(order_id, sku_id)`，即使修正 Redis 幂等键，也需要同步修正 Outbox 事件模型，避免不同 action 互相覆盖。

## 是否需要补充验证

需要构造一个包含两个 SKU 的已支付订单，执行一次退款回调，核对两个 SKU 的 Redis total、桶库存、MySQL available_stock 和 MQ/Outbox 记录。
