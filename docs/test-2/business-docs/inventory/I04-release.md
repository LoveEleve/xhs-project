# I04: 释放库存 — POST /api/inventory/release

## § 源码分析
- **Controller**: `InventoryController.java:94` → `@PostMapping("/release")`, X-Internal-Call
- **Service**: `InventoryService.java:377` → `releaseStock()`
  - 删预扣记录: DEL inventory:prededuct:{orderId}
  - MQ: syncSend INVENTORY_TOPIC:RELEASE → Consumer: available_stock += qty
- **下游**: Redis prededuct + MQ RELEASE → MySQL t_inventory

## § 业务逻辑
order取消/超时→Feign I04→删除预扣记录→MQ RELEASE→Consumer恢复Redis库存

## § curl

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | `curl Nacos .../my-xhs-inventory` | Gateway 503 |
```bash
curl -s -X POST http://localhost:19000/api/inventory/release \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -d '{"orderId":"ORD-001","skuId":123,"pseudoOrderId":"hash-xxx"}'
```

## § ASCII流转图
```
Feign order → inventory:19005 I04 release
  → DEL inventory:prededuct:{orderId}
  → MQ RELEASE → InventoryDeductConsumer: available_stock+=qty
```

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | Outbox/Retry兜底 | ✅ |
