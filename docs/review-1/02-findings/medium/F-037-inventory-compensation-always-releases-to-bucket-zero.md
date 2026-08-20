# F-037 库存补偿重试固定使用 bucket 0，无法恢复实际预扣桶

## 严重度

Medium

## 涉及文件

- `my-xhs-inventory/src/main/java/com/myxhs/inventory/job/InventoryCompensationJob.java:74-81`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java:458-469`

## 现象

正常 `releaseStock()` 会从预扣 hash 中读取 `${skuId}:bucket`，使用实际扣减来源桶；库存补偿任务却固定把 `bucketKey(skuId, 0)` 传给 `releaseScript`。

## 证据

1. 正常释放路径读取预扣记录中的 bucket：`InventoryService.java:458-463`。
2. 补偿任务固定传入 bucket 0：`InventoryCompensationJob.java:78-81`。
3. 预扣逻辑会按 userId/bucketCount 选择来源桶，并将桶号写入预扣记录。

## 触发条件

1. 预扣从 bucket N（N>0）扣减。
2. 正常释放失败，进入 `t_inventory_compensation`。
3. 补偿任务执行释放。

## 影响

1. Redis total 可能恢复，但 bucket N 不恢复、bucket 0 被增加。
2. 分桶负载失真，后续按桶扣减可能出现热点、容量分布错误或桶和总量不一致。
3. 补偿成功日志会掩盖“恢复到了错误桶”的状态。

## 修复建议

1. 补偿记录保存实际 bucketNo，或从 prededuct hash 读取对应 bucket。
2. 复用 `releaseStock()` 的统一释放逻辑，避免补偿路径复制一套不完整的 key 计算。
3. 补充非 bucket0 预扣失败/释放失败的补偿测试，核对各桶与 total。

## 是否需要补充验证

需要让 userId 路由到非 0 bucket，制造释放失败后执行补偿，比较原 bucket 与 bucket0 的变化。