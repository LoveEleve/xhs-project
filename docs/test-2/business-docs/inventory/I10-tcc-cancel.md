# I10: TCC Cancel — POST /api/inventory/tcc/cancel

## § 源码分析

- **Controller**: `InventoryController.java:195` → `@PostMapping("/tcc/cancel")`, X-Internal-Call
- **Service**: `InventoryTccService.java:92` → `cancelDeductStock()`
  - `cancelFence()`: 空回滚(INSERT status=3→SKIP)/乐观锁(UPDATE 1→3→EXECUTE)
  - `cancelFreeze()`: freezing_stock -= qty, available_stock += qty
  - UPDATE t_tcc_freeze_detail SET status=3
- **下游**: MySQL t_tcc_fence + t_inventory

## § 业务逻辑

order回滚→Feign I10→空回滚判断(Try未执行→SKIP)→乐观锁解冻(freezing_stock→available_stock)→更新明细status=3

## § curl

```bash
curl -s -X POST http://localhost:19000/api/inventory/tcc/cancel \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -d '{"xid":"tx-001","branchId":1}'
```

## § ASCII流转图

```
Feign order → inventory:19005 I10 TCC Cancel
  → InventoryTccService.cancelDeductStock()
    → cancelFence()
      → INSERT status=3成功 → 空回滚 → SKIP
      → 冲突status=2 → REJECTED_CONFIRMED
      → 冲突status=1 → UPDATE 1→3 → EXECUTE
    → cancelFreeze(): freezing_stock -= qty, available_stock += qty
    → UPDATE t_tcc_freeze_detail SET status=3
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_tcc_fence WHERE xid=?` | 3(取消)或1(Try仍在) |
| MySQL | `SELECT available_stock FROM t_inventory WHERE sku_id=?` | 恢复原值 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | 空回滚+乐观锁+防悬挂 | ✅ |

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Internal-Call | Header匹配 | 403 |
