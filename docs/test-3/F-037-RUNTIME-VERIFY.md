# F-037 运行态验证步骤

## 目标
验证库存补偿在非 `bucket0` 场景下，能把库存恢复回实际预扣桶，而不是错误写回 `bucket0`。

## 前提
- 使用隔离环境
- `my-xhs-inventory` 服务已部署本轮修复代码
- 可访问 Redis 和 XXL/调度日志

## 步骤
1. 选择一个 `bucketCount > 1` 的 SKU，例如 `skuId=10001`，确认存在 `inventory:bucket:count:{10001}`。
2. 选择一个会路由到非 0 bucket 的用户，例如当 `bucketCount=2` 时，选奇数 `userId`，使 `userId % 2 = 1`。
3. 执行一次预扣，记录：
   - `inventory:{10001}:total`
   - `inventory:{10001}:bucket:0`
   - `inventory:{10001}:bucket:1`
   - `inventory:prededuct:{orderId}` 中 `10001` 和 `10001:bucket`
4. 人为制造正常释放失败，让记录进入 `t_inventory_compensation`。
5. 触发 `InventoryCompensationJob` 或等待定时任务执行。
6. 对比补偿前后：
   - `inventory:{10001}:bucket:1` 应恢复预扣数量
   - `inventory:{10001}:bucket:0` 不应被错误增加
   - `inventory:{10001}:total` 与各 bucket 求和一致
   - `t_inventory_compensation.status=1`
7. 检查日志关键字：
   - `[库存补偿] 回退成功`
   - 不应出现固定 `bucket:0` 的错误恢复痕迹

## 通过标准
- 实际来源桶恢复，非来源桶不漂移
- total 与 bucket sum 一致
- 补偿记录正确完成

## 备注
若需要构造特定路由，可用 `userId % bucketCount` 预先计算目标 bucket。
