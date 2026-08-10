# I02: 库存预扣 — POST /api/inventory/preDeduct

## § 源码分析

- **Controller**: `InventoryController.java:70` → `@PostMapping("/preDeduct")`, X-Internal-Call
- **Service**: `InventoryService.java:207` → `preDeduct()`
  - Redis Lua原子: 多桶递减 inventory:{skuId}:bucket:{n}
  - 写预扣记录: `inventory:prededuct:{orderId}` TTL=30min
  - Outbox: INSERT t_inventory_outbox(sku_id, order_id, quantity, action=PRE_DEDUCT)
  - MQ: syncSend INVENTORY_TOPIC:PRE_DEDUCT
- **下游**: Redis桶分片 + MySQL t_inventory_outbox + MQ

## § 业务逻辑

order创建→Feign预扣→Redis Lua多桶原子递减→写预扣记录(30min)→Outbox落库→MQ发送→成功markOutboxSent/失败cancelOutboxEvent

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Internal-Call | Header匹配 | 403 |
| 库存充足 | `r.get('inventory:{skuId}:total')>0` | 预扣失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -H "X-Internal-Call:token" POST /api/inventory/preDeduct -d '{...}'` | 200 |
| Redis | `r.get('inventory:{skuId}:total')` | 减少qty |
| MySQL | `SELECT * FROM t_inventory_outbox WHERE order_id=?` | 1行 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Redis Lua原子多桶 | ✅ |
| 可靠性 | Outbox+Job兜底 | ✅ |
| 幂等 | uk_order_sku+ON DUPLICATE KEY | ✅ |

## § curl

```bash
INTERNAL_TOKEN=$(grep internal.token /data/workspace/my-xhs/*/src/main/resources/application.yml | head -1 | awk '{print $NF}')
curl -s -i -X POST http://localhost:19000/api/inventory/preDeduct \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"skuId":123,"orderId":"ORD-001","quantity":1}'
```

## § ASCII流转图

```
Feign order → inventory:19005 I02 preDeduct
  → InventoryService.preDeduct()
    → Redis Lua: 多桶DECR inventory:{skuId}:bucket:{0..N}
    → Redis SET inventory:prededuct:{orderId} TTL=1800
    → MySQL: INSERT t_inventory_outbox(...)
    → MQ: syncSend INVENTORY_TOPIC:PRE_DEDUCT
      → 成功→markOutboxSent/失败→cancelOutboxEvent
    → 返回成功
```
