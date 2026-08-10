# I07: 对账 — POST /api/inventory/internal/reconcile

## § 源码分析
- **Controller**: `InventoryController.java:134` → `@PostMapping("/internal/reconcile")`, X-Admin-Call
- **Service**: `InventoryReconcileJob.java:73` → `doReconcile()`
  - 游标扫描 t_inventory → 对比Redis total
  - 分桶Lua原子校验: 各bucket之和 vs total
  - 不一致 → MySQL修正Redis
- **下游**: Redis桶分片 + MySQL t_inventory

## § 业务逻辑
管理员对账→游标扫MySQL→对比Redis total+桶和→不一致修正

## § curl

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | `curl Nacos .../my-xhs-inventory` | Gateway 503 |
```bash
curl -s -X POST http://localhost:19000/api/inventory/internal/reconcile \
  -H "X-Admin-Call: $ADMIN_TOKEN"
```

## § ASCII流转图
```
Admin → inventory:19005 I07 reconcile
  → 游标: SELECT * FROM t_inventory
  → 对比: Redis GET inventory:{skuId}:total vs MySQL available_stock
  → Lua: SUM(bucket:0..N) vs total
  → 不一致 → MySQL修正Redis
```

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | Outbox/Retry兜底 | ✅ |
