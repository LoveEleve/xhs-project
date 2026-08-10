# I03: 确认扣减 — POST /api/inventory/confirm

## § 源码分析
- **Controller**: `InventoryController.java:82` → `@PostMapping("/confirm")`, X-Internal-Call
- **Service**: `InventoryService.java:306` → `confirmDeduct()`
  - 删预扣记录: DEL inventory:prededuct:{orderId}
  - Outbox: INSERT/UPDATE t_inventory_outbox(action=CONFIRM)
  - MQ: syncSend INVENTORY_TOPIC:CONFIRM → Consumer: locked_stock += qty, available_stock -= qty
- **下游**: Redis prededuct + MQ CONFIRM → MySQL t_inventory

## § 业务逻辑
order支付成功→Feign I03→删除预扣记录→Outbox落库→MQ CONFIRM→Consumer异步扣减MySQL库存

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Internal-Call | Header匹配 | 403 |
| 预扣记录存在 | `r.get('inventory:prededuct:{orderId}')` | 跳过 |

## § curl
```bash
curl -s -X POST http://localhost:19000/api/inventory/confirm \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -d '{"orderId":"ORD-001","skuId":123}'
```

## § ASCII流转图
```
Feign order → inventory:19005 I03 confirm
  → DEL inventory:prededuct:{orderId}
  → Outbox: INSERT t_inventory_outbox(action=CONFIRM)
  → MQ CONFIRM → InventoryDeductConsumer: locked_stock+=qty
```

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | Outbox/Retry兜底 | ✅ |
