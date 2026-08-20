# F-005 库存 Outbox 以 order_id+sku_id 唯一，多个动作会互相覆盖

## 严重度

Medium

## 涉及服务

- `my-xhs-inventory`

## 涉及文件

- `sql/mysql-inventory-init.sql:47-58`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/mapper/InventoryMapper.java:130-154`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/job/InventoryOutboxSenderJob.java:51-87`

## 现象

`t_inventory_outbox` 的唯一索引是：

```sql
UNIQUE INDEX uk_order_sku (order_id, sku_id)
```

但 outbox 的业务事件类型至少包括 `PRE_DEDUCT`、`CONFIRM`、`RELEASE`。同一订单同一 SKU 在生命周期中可能产生多个不同 action，却只能共用一行。

## 证据

1. 表结构 `sql/mysql-inventory-init.sql:52-57` 的唯一键不包含 `action`。
2. `InventoryMapper.insertOutboxEvent()` 使用 `ON DUPLICATE KEY UPDATE`，重复写入时会覆盖 `quantity`、`action`、`status`、`created_at`：`InventoryMapper.java:130-134`。
3. sender 扫描 pending 行并按当前 `action` 发消息：`InventoryOutboxSenderJob.java:51-79`。
4. sender 标记已发送时只按 `order_id + sku_id` 更新：`InventoryMapper.java:146-147`，没有 action 条件。
5. 取消 pending 行时同样只按 `order_id + sku_id` 删除：`InventoryMapper.java:153-154`。

## 触发条件

同一订单同一 SKU 先后或并发产生两个动作，例如：

1. PRE_DEDUCT 已落 outbox，尚未发送
2. 随后 RELEASE 或 CONFIRM 又落 outbox
3. 第二次写入覆盖第一条 action

或者：

1. 某 action 已发送但标记/回滚存在延迟
2. 另一 action 复用同一唯一键
3. 任一 mark/delete 操作影响同一 order+sku 的当前行

## 影响

按单一订单+SKU 的顺序生命周期（PRE_DEDUCT→CONFIRM，或 PRE_DEDUCT→RELEASE，或再叠加 REFUND_RESTORE），每次动作都是"插入→发送→标记 sent"串行完成，happy path 下单行覆盖不会丢事件。

真正风险集中在：

1. 上一个动作发送失败残留 `status=0`，下一动作 `ON DUPLICATE` 覆盖该行，旧动作的待发送状态被丢弃（旧动作通常因订单失败而回滚，但若覆盖发生在回滚前存在窗口）。
2. `markOutboxSent`/`cancelOutboxEvent` 只按 `(order_id, sku_id)` 定位，无法区分是哪一次动作，异常时序下可能误标/误删另一动作。
3. 同一 `(order_id, sku_id)` 的版本/事件语义被多 action 复用，`InventoryDeductConsumer` 的版本检查（按 order+sku 不按 action）在动作重叠时会互相干扰。
4. 设计上无法表达"同一订单同一 SKU 需要多次独立事件"，扩展性差。

## 修复建议

1. 重新定义 outbox 唯一键，至少包含 `(order_id, sku_id, action)`；如果同一 action 也可能多次发生，需要使用业务事件 ID。
2. `markOutboxSent`、`cancelOutboxEvent`、重试更新必须带 outbox 主键或事件 ID，不能只按 order+sku。
3. 明确 PRE_DEDUCT/CONFIRM/RELEASE 的顺序语义，并让消费者按事件版本或状态机处理乱序。
4. 对已有表执行结构迁移前，先盘点历史 outbox 行和重复动作。

## 是否需要补充验证

需要用同一 orderId+skuId 模拟 PRE_DEDUCT→CONFIRM 和 PRE_DEDUCT→RELEASE 两种路径，检查数据库最终 outbox 行数、action、发送记录与 MySQL 库存状态。