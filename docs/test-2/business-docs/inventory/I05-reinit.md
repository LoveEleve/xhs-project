# I05: 重建库存 — POST /api/inventory/reinit

## § 源码分析
- **Controller**: `InventoryController.java:110` → `@PostMapping("/reinit")`, X-Admin-Call, @RateLimit
- **Service**: `InventoryService.java:687` → `reinitStock()`
  - SCAN清空Redis所有桶: SCAN+DEL inventory:*:bucket:*
  - MySQL: SELECT * FROM t_inventory
  - 重建Redis桶分片+total
- **下游**: Redis SCAN+MySQL t_inventory

## § 业务逻辑
管理员执行→SCAN清空Redis桶→从MySQL读取库存→按原规则重建桶分片

## § curl

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | `curl Nacos .../my-xhs-inventory` | Gateway 503 |
```bash
curl -s -X POST http://localhost:19000/api/inventory/reinit \
  -H "X-Admin-Call: $ADMIN_TOKEN"
```

## § ASCII流转图
```
Admin → inventory:19005 I05 reinit
  → Redis: SCAN DEL inventory:*:bucket:*
  → MySQL: SELECT * FROM t_inventory
  → Redis: 重建桶分片+total
```

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | Outbox/Retry兜底 | ✅ |
