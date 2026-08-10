# I08: TCC Try — POST /api/inventory/tcc/try

## § 源码分析

- **Controller**: `InventoryController.java:155` → `@PostMapping("/tcc/try")`, X-Internal-Call, `tryDeductStock()`
- **Service**: `InventoryTccService.java:35` → `tryDeductStock()`
  - `TccFenceService.tryFence(xid, branchId)` → INSERT t_tcc_fence(status=1)
  - 悬挂判定: DuplicateKeyException + status=3 → SUSPENDED
  - 幂等命中: DuplicateKeyException + status≠3 → DUPLICATE
  - `inventoryMapper.tryFreeze()`: UPDATE available_stock-=qty, freezing_stock+=qty
  - INSERT t_tcc_freeze_detail(xid, branchId, skuId, qty, status=1)
- **下游**: MySQL t_tcc_fence + t_tcc_freeze_detail + t_inventory

## § 业务逻辑

order→Feign调用(TCC Try)→Fence防悬挂检查→幂等校验→冻结库存(available_stock→freezing_stock)→写冻结明细→返回成功/悬挂/重复→调用方据此决定COMMIT(CONFIRM)或ROLLBACK(CANCEL)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Internal-Call | Header匹配 `${myxhs.internal.token}` | 403 |
| 库存充足 | `SELECT available_stock>=qty FROM t_inventory` | 冻结失败 |
| inventory服务注册 | `curl Nacos .../my-xhs-inventory` | 503 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -H "X-Internal-Call:token" POST /api/inventory/tcc/try` | 200 |
| MySQL | `SELECT * FROM t_tcc_fence WHERE xid=?` | 1行, status=1 |
| MySQL | `SELECT freezing_stock FROM t_inventory WHERE sku_id=?` | 冻结数量 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | TCC Fence防悬挂+幂等+空回滚 | ✅ |
| 并发 | xid+branch_id PK防重复 | ✅ |
| 弹性 | TccTimeoutJob 10min兜底 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/admin_token.txt)
curl -s -i -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"xid":"tx-001","branchId":1,"skuId":123,"quantity":5}'
```

## § ASCII流转图

```
Feign order → inventory:19005 I08 TCC Try
  → InventoryTccService.tryDeductStock()
    → TccFenceService.tryFence()
      → INSERT t_tcc_fence(xid, branchId, status=1)
        → DuplicateKeyException?→status=3→SUSPENDED/status≠3→DUPLICATE
    → inventoryMapper.tryFreeze()
      → UPDATE t_inventory SET available_stock-=qty, freezing_stock+=qty
    → INSERT t_tcc_freeze_detail(xid, branchId, skuId, quantity, status=1)
    → 返回 SUCCESS
```
